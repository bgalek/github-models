#!/usr/bin/env node
import fs from "node:fs";
import https from "node:https";
import {dirname} from "node:path";

const URL = process.env.OPENAPI_URL ||
    'https://raw.githubusercontent.com/github/rest-api-description/refs/heads/main/descriptions/ghes-3.20/ghes-3.20.json';
const OUT = process.argv[2] || 'ghes-3.20.preprocessed.json';

function fetch(url) {
    return new Promise((resolve, reject) => {
        https.get(url, (res) => {
            if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
                return resolve(fetch(res.headers.location));
            }
            if (res.statusCode !== 200) return reject(new Error(`HTTP ${res.statusCode}`));
            let data = '';
            res.setEncoding('utf8');
            res.on('data', (c) => (data += c));
            res.on('end', () => resolve(data));
        }).on('error', reject);
    });
}

function pascal(s) {
    return String(s)
        .replace(/[^a-zA-Z0-9]+(.)/g, (_, c) => c.toUpperCase())
        .replace(/^./, (c) => c.toUpperCase());
}

// Classify a oneOf/anyOf variant: 'scalar' for an inline primitive, 'null' for
// the JSON null type, or null for anything else (refs, objects, arrays, nested
// compositions) that must not be collapsed.
function variantKind(node) {
    if (!node || typeof node !== 'object' || node.$ref) return null;
    if (node.properties || node.additionalProperties || node.items ||
        node.allOf || node.oneOf || node.anyOf) return null;
    if (node.type === 'null') return 'null';
    if (['string', 'integer', 'number', 'boolean'].includes(node.type)) return 'scalar';
    return null;
}

// GitHub's spec models fields like `repository.created_at` as
// `oneOf: [{type: integer}, {type: string, format: date-time}]`. openapi-generator's
// Java generator emits an empty placeholder class for such scalar unions, which Jackson
// cannot construct from a number or string — every payload carrying one fails to
// deserialize. Collapse any union whose variants are all primitive scalars (optionally
// including the null type) into a free-form schema so the generator emits a permissive
// `Object` field that accepts whichever scalar arrives.
function collapseScalarUnions(node) {
    if (!node || typeof node !== 'object') return;
    if (Array.isArray(node)) {
        node.forEach(collapseScalarUnions);
        return;
    }
    for (const key of ['oneOf', 'anyOf']) {
        const variants = node[key];
        if (!Array.isArray(variants) || variants.length === 0) continue;
        const kinds = variants.map(variantKind);
        if (kinds.every((k) => k !== null) && kinds.includes('scalar')) {
            delete node[key];
            delete node.title;
            delete node.discriminator;
            delete node.type;
            delete node.format;
            if (kinds.includes('null')) node.nullable = true;
        }
    }
    for (const value of Object.values(node)) collapseScalarUnions(value);
}

// Walk a schema node and set `title` on inline object/enum schemas so
// openapi-generator names them using the parent context instead of
// auto-generating colliding names like Repository1, Repository2, ...
function annotate(node, parentName, seenObjects) {
    if (!node || typeof node !== 'object') return;
    if (Array.isArray(node)) {
        node.forEach((n) => annotate(n, parentName, seenObjects));
        return;
    }
    // Skip $ref nodes; their target is handled separately.
    if (node.$ref) return;

    const isObject =
        node.type === 'object' ||
        (!node.type && (node.properties || node.additionalProperties || node.allOf || node.oneOf || node.anyOf));
    const isEnum = Array.isArray(node.enum);

    if ((isObject || isEnum) && parentName) {
        // Avoid rewriting titles that are already set intentionally and unique.
        if (!node.title || seenObjects.has(node.title)) {
            node.title = parentName;
        }
        seenObjects.add(node.title);
    }

    const base = node.title || parentName || '';

    if (node.properties && typeof node.properties === 'object') {
        for (const [k, v] of Object.entries(node.properties)) {
            annotate(v, base + pascal(k), seenObjects);
        }
    }
    if (node.items) annotate(node.items, base + 'Item', seenObjects);
    if (node.additionalProperties && typeof node.additionalProperties === 'object') {
        annotate(node.additionalProperties, base + 'Value', seenObjects);
    }
    for (const key of ['allOf', 'oneOf', 'anyOf']) {
        if (Array.isArray(node[key])) {
            node[key].forEach((sub, i) => annotate(sub, base + pascal(key) + i, seenObjects));
        }
    }
    if (node.not) annotate(node.not, base + 'Not', seenObjects);
}

(async () => {
    console.log('Fetching spec...');
    const text = await fetch(URL);
    const spec = JSON.parse(text);

    // Collapse scalar oneOf/anyOf unions before naming, so the discarded
    // variants never get titles and no empty placeholder classes are generated.
    collapseScalarUnions(spec);

    const seen = new Set();

    // Named top-level schemas: use their own name as the base.
    const schemas = (spec.components && spec.components.schemas) || {};
    for (const [name, schema] of Object.entries(schemas)) {
        if (schema && typeof schema === 'object') {
            schema.title = schema.title || pascal(name);
            seen.add(schema.title);
        }
    }
    for (const [name, schema] of Object.entries(schemas)) {
        annotate(schema, pascal(name), seen);
    }

    // Webhooks / paths: walk inline request/response bodies so inline
    // objects get titles prefixed by the operationId.
    const walkOp = (op) => {
        if (!op || typeof op !== 'object') return;
        const base = pascal(op.operationId || '');
        if (op.requestBody) annotate(op.requestBody, base + 'Request', seen);
        if (op.responses) {
            for (const [code, resp] of Object.entries(op.responses)) {
                annotate(resp, base + 'Response' + code, seen);
            }
        }
        if (op.parameters) annotate(op.parameters, base + 'Param', seen);
    };
    const walkPaths = (paths) => {
        if (!paths) return;
        for (const [_, item] of Object.entries(paths)) {
            for (const [m, op] of Object.entries(item || {})) {
                if (['get', 'post', 'put', 'patch', 'delete', 'options', 'head', 'trace'].includes(m)) {
                    walkOp(op);
                }
            }
        }
    };
    walkPaths(spec.paths);
    walkPaths(spec.webhooks);

    const outDir = dirname(OUT);
    if (outDir && outDir !== '.') fs.mkdirSync(outDir, {recursive: true});
    fs.writeFileSync(OUT, JSON.stringify(spec, null, 2));
    console.log(`Wrote ${OUT} (${(fs.statSync(OUT).size / 1024 / 1024).toFixed(1)} MB)`);
})().catch((e) => {
    console.error(e);
    process.exit(1);
});

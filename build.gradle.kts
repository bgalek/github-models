import com.github.gradle.node.task.NodeTask
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    `java-library`
    `maven-publish`
    signing
    alias(libs.plugins.openapi.generator)
    alias(libs.plugins.node.gradle)
    alias(libs.plugins.axion.release)
    alias(libs.plugins.nexus.publish)
}

group = "com.github.bgalek.github"
version = scmVersion.version

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

node {
    download = true
    version = "25.9.0"
}

data class Variant(val name: String, val specUrl: String, val modelPackage: String)

val variants = listOf(
    Variant(
        name = "ghes",
        specUrl = "https://raw.githubusercontent.com/github/rest-api-description/refs/heads/main/descriptions/ghes-3.20/ghes-3.20.json",
        modelPackage = "com.github.bgalek.github.ghes.models",
    ),
    Variant(
        name = "dotcom",
        specUrl = "https://raw.githubusercontent.com/github/rest-api-description/refs/heads/main/descriptions/api.github.com/api.github.com.json",
        modelPackage = "com.github.bgalek.github.dotcom.models",
    ),
)

val variantJars = mutableListOf<TaskProvider<Jar>>()
val variantSourcesJars = mutableListOf<TaskProvider<Jar>>()
val variantJavadocJars = mutableListOf<TaskProvider<Jar>>()

variants.forEach { v ->
    val capitalized = v.name.replaceFirstChar { it.uppercase() }
    val preprocessedSpec = layout.buildDirectory.file("openapi/${v.name}.preprocessed.json")
    val generatedDir = layout.buildDirectory.dir("generated/${v.name}")
    val generatedSrc = layout.buildDirectory.dir("generated/${v.name}/src/main/java")

    val preprocessTask = tasks.register<NodeTask>("preprocessOpenapi$capitalized") {
        description = "Preprocess the ${v.name} OpenAPI spec."
        script = file("preprocess-openapi.mjs")
        args = listOf(preprocessedSpec.get().asFile.absolutePath)
        environment = mapOf("OPENAPI_URL" to v.specUrl)
        inputs.file("preprocess-openapi.mjs")
        inputs.property("url", v.specUrl)
        outputs.file(preprocessedSpec)
    }

    val generateTask = tasks.register<GenerateTask>("generateModels$capitalized") {
        description = "Generate Java models for ${v.name}."
        dependsOn(preprocessTask)
        generatorName = "java"
        inputSpec = preprocessedSpec.map { it.asFile.absolutePath }
        outputDir = generatedDir.map { it.asFile.absolutePath }
        modelPackage = v.modelPackage
        configOptions = mapOf(
            "serializationLibrary" to "jackson",
            "useJackson3" to "true",
            "useJspecify" to "true",
            "useSpringBoot4" to "true",
            "library" to "webclient",
            "openApiNullable" to "false",
            "useJakartaEe" to "true",
        )
        globalProperties = mapOf(
            "modelTests" to "false",
            "apiTests" to "false",
            "modelDocs" to "false",
            "models" to "",
        )
    }

    // GitHub's spec has a top-level `content-directory` array schema referenced as a
    // oneOf variant. openapi-generator emits the item as ContentDirectoryItem and
    // drops the wrapper, leaving @JsonSubTypes(ContentDirectory.class) dangling.
    val stubTask = tasks.register("emitContentDirectoryStub$capitalized") {
        dependsOn(generateTask)
        val pkgPath = v.modelPackage.replace('.', '/')
        val outDir = layout.buildDirectory.dir("generated/${v.name}/src/main/java/$pkgPath")
        outputs.dir(outDir)
        val pkg = v.modelPackage
        doLast {
            val dir = outDir.get().asFile.apply { mkdirs() }
            val target = dir.resolve("ContentDirectory.java")
            if (target.exists()) return@doLast
            target.writeText(
                """
                package $pkg;

                import java.util.ArrayList;
                import java.util.Collection;

                public class ContentDirectory extends ArrayList<ContentDirectoryItem> {
                    public ContentDirectory() { super(); }
                    public ContentDirectory(Collection<? extends ContentDirectoryItem> c) { super(c); }
                }
                """.trimIndent() + "\n",
            )
        }
    }

    val variantSourceSet = sourceSets.create(v.name) {
        java.setSrcDirs(listOf(generatedSrc))
    }

    tasks.named<JavaCompile>(variantSourceSet.compileJavaTaskName) {
        dependsOn(generateTask, stubTask)
    }

    val artifactBase = "github-models-${v.name}"

    val jarTask = tasks.register<Jar>("${v.name}Jar") {
        dependsOn(variantSourceSet.classesTaskName)
        archiveBaseName = artifactBase
        from(variantSourceSet.output)
        manifest {
            attributes(
                "Implementation-Title" to artifactBase,
                "Implementation-Version" to project.version,
            )
        }
    }

    val sourcesJarTask = tasks.register<Jar>("${v.name}SourcesJar") {
        dependsOn(generateTask, stubTask)
        archiveBaseName = artifactBase
        archiveClassifier = "sources"
        from(generatedSrc)
    }

    val javadocJarTask = tasks.register<Jar>("${v.name}JavadocJar") {
        archiveBaseName = artifactBase
        archiveClassifier = "javadoc"
    }

    variantJars += jarTask
    variantSourcesJars += sourcesJarTask
    variantJavadocJars += javadocJarTask
}

dependencies {
    variants.forEach { v ->
        add("${v.name}CompileOnly", libs.jackson.annotations)
        add("${v.name}CompileOnly", libs.jackson.databind)
        add("${v.name}CompileOnly", libs.jspecify)
        add("${v.name}CompileOnly", libs.jakarta.annotation.api)
    }
}

// Disable the default `main` jar since we produce per-variant jars.
tasks.named<Jar>("jar") { enabled = false }

tasks.named("assemble") {
    dependsOn(variantJars, variantSourcesJars, variantJavadocJars)
}

val publishedRuntimeDeps: List<Triple<String, String, String>> = listOf(
    libs.jackson.annotations,
    libs.jackson.databind,
    libs.jspecify,
    libs.jakarta.annotation.api,
).map { provider ->
    val d = provider.get()
    Triple(d.group!!, d.name, d.version!!)
}

publishing {
    publications {
        variants.forEachIndexed { idx, v ->
            create<MavenPublication>(v.name) {
                artifactId = "github-models-${v.name}"
                artifact(variantJars[idx])
                artifact(variantSourcesJars[idx])
                artifact(variantJavadocJars[idx])
                pom {
                    name = "github-models-${v.name}"
                    description = "Generated Java models for the GitHub ${v.name} REST API event payloads."
                    url = "https://github.com/bgalek/github-models/"
                    inceptionYear = "2025"
                    licenses {
                        license {
                            name = "The Apache License, Version 2.0"
                            url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                        }
                    }
                    developers {
                        developer {
                            id = "bgalek"
                            name = "Bartosz Gałek"
                            email = "bartosz@galek.com.pl"
                        }
                    }
                    scm {
                        connection = "scm:git:git://github.com/bgalek/github-models.git"
                        developerConnection = "scm:git:ssh://github.com:bgalek/github-models.git"
                        url = "https://github.com/bgalek/github-models/"
                    }
                    val pomDeps = publishedRuntimeDeps
                    withXml {
                        val deps = asNode().appendNode("dependencies")
                        pomDeps.forEach { (group, name, version) ->
                            deps.appendNode("dependency").apply {
                                appendNode("groupId", group)
                                appendNode("artifactId", name)
                                appendNode("version", version)
                                appendNode("scope", "compile")
                            }
                        }
                    }
                }
            }
        }
    }
}

nexusPublishing {
    repositories {
        sonatype {
            username = System.getenv("SONATYPE_USERNAME")
            password = System.getenv("SONATYPE_PASSWORD")
            nexusUrl = uri("https://ossrh-staging-api.central.sonatype.com/service/local/")
            snapshotRepositoryUrl = uri("https://central.sonatype.com/repository/maven-snapshots/")
        }
    }
}

signing {
    setRequired { System.getenv("GPG_KEY_ID") != null }
    useInMemoryPgpKeys(
        System.getenv("GPG_KEY_ID"),
        System.getenv("GPG_PRIVATE_KEY"),
        System.getenv("GPG_PRIVATE_KEY_PASSWORD"),
    )
    sign(publishing.publications)
}

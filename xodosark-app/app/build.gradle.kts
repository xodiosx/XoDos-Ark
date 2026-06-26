import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    kotlin("android")    // ← must be present
}

// Optional release signing — copy keystore.properties.example → keystore.properties (gitignored).
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

android {
    namespace = "app.xodos2"
    compileSdk = 34
//buildToolsVersion = "34.0.0"  
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
kotlinOptions {
        jvmTarget = "11"          
    }
        defaultConfig {
        applicationId = "app.xodos2"
        minSdk = 26
        targetSdk = 34
      
        ndkVersion = "27.1.12297006"   
        buildConfigField("String", "COMMIT", "\"xodos2-embedded-x11\"")
        // versionName: user-visible, align with git tag / Release (e.g. v0.1.0 → "0.1.0").
        // versionCode: positive integer, must increase for every new APK you ship (Play / sideload).
        versionCode = 6
        versionName = "0.6.0"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                keyAlias = keystoreProperties.getProperty("keyAlias")!!
                keyPassword = keystoreProperties.getProperty("keyPassword")!!
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile")!!)
                storePassword = keystoreProperties.getProperty("storePassword")!!
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Official OSS releases: use keystore.properties. Without it, release is signed with the debug key (local testing only).
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += "**/libtermux.so"
        }
    }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
    sourceSets.getByName("main").java.srcDir(layout.buildDirectory.dir("generated/source/prefs/java"))
}

private data class PrefGen(
    val type: String,
    val key: String,
    val defaultV: String,
    val entries: String? = null,
    val values: String? = null,
)

val generatePrefs by tasks.registering {
    val prefXml = layout.projectDirectory.file("src/main/res/xml/preferences.xml")
    val outDir = layout.buildDirectory.dir("generated/source/prefs/java")
    inputs.file(prefXml)
    outputs.dir(outDir)
    doLast {
        val ns = "http://schemas.android.com/apk/res-auto"
        fun Element.attr(name: String): String {
            val v = getAttributeNS(ns, name)
            return v.ifEmpty { getAttribute(name) }
        }
        fun stripArrayRef(raw: String): String =
            raw.removePrefix("@array/").removePrefix("@string/")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(prefXml.asFile)
        val prefs = mutableListOf<PrefGen>()
        fun walk(n: Node) {
            if (n !is Element) return
            when (n.localName) {
                "EditTextPreference" -> {
                    val key = n.attr("key")
                    if (key.isNotEmpty() && key != "extra_keys_config") {
                        prefs.add(PrefGen("String", key, n.attr("defaultValue")))
                    }
                }
                "SeekBarPreference" ->
                    prefs.add(PrefGen("Int", n.attr("key"), n.attr("defaultValue")))
                "ListPreference" ->
                    prefs.add(
                        PrefGen(
                            "List",
                            n.attr("key"),
                            n.attr("defaultValue"),
                            stripArrayRef(n.attr("entries")),
                            stripArrayRef(n.attr("entryValues")),
                        ),
                    )
                "SwitchPreferenceCompat" ->
                    prefs.add(PrefGen("Boolean", n.attr("key"), n.attr("defaultValue")))
            }
            for (i in 0 until n.childNodes.length) walk(n.childNodes.item(i))
        }
        walk(doc.documentElement)
        val outFile = outDir.get().asFile.resolve("com/termux/x11/Prefs.java")
        outFile.parentFile?.mkdirs()
        outFile.writeText(buildString {
            appendLine("package com.termux.x11;")
            appendLine("import java.util.HashMap;")
            appendLine("import android.content.Context;")
            appendLine("import com.termux.x11.utils.TermuxX11ExtraKeys;")
            appendLine("import app.xodos2.R;")
            appendLine()
            appendLine("public class Prefs extends LoriePreferences.PrefsProto {")
            for (p in prefs) {
                when (p.type) {
                    "Int", "Boolean" ->
                        appendLine("  public final ${p.type}Preference ${p.key} = new ${p.type}Preference(\"${p.key}\", ${p.defaultV});")
                    "String" ->
                        appendLine("  public final StringPreference ${p.key} = new StringPreference(\"${p.key}\", \"${p.defaultV}\");")
                    "List" ->
                        appendLine(
                            "  public final ListPreference ${p.key} = new ListPreference(\"${p.key}\", \"${p.defaultV}\", R.array.${p.entries}, R.array.${p.values});",
                        )
                }
            }
            appendLine("  public final StringPreference extra_keys_config = new StringPreference(\"extra_keys_config\", TermuxX11ExtraKeys.DEFAULT_IVALUE_EXTRA_KEYS);")
            appendLine("  public final HashMap<String, Preference> keys = new HashMap<>() {{")
            for (p in prefs) appendLine("    put(\"${p.key}\", ${p.key});")
            appendLine("    put(\"extra_keys_config\", extra_keys_config);")
            appendLine("  }};")
            appendLine()
            appendLine("  public Prefs(Context ctx) {")
            appendLine("    super(ctx);")
            appendLine("  }")
            appendLine("}")
        })
    }
}

afterEvaluate {
    tasks.named("preBuild").configure { dependsOn(generatePrefs) }
}

dependencies {
implementation("org.jsoup:jsoup:1.17.2")
implementation("androidx.compose.material:material-icons-extended:1.5.0") //
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference:1.2.1")
    // terminal-emulator artifact (VT + screen buffer); terminal-view sources live under com/termux/view/
    implementation("com.termux.termux-app:terminal-emulator:0.118.0")
    // ProfileInstaller + androidx.concurrent need a real ListenableFuture on the classpath; the "9999.0-empty"
    // artifact is a zero-class placeholder and causes NoClassDefFoundError on pool-* threads at runtime.
    implementation("com.google.guava:listenablefuture:1.0")
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)
    implementation("org.apache.commons:commons-compress:1.26.1")
implementation("org.tukaani:xz:1.9")
implementation("org.apache.commons:commons-compress:1.26.1")
implementation("org.tukaani:xz:1.9")

implementation("com.google.android.material:material:1.11.0")
  implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    

}

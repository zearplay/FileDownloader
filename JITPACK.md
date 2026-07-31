# JitPack usage

Release coordinate:

```gradle
repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.zearplay:FileDownloader:1.7.11-anrfix")
}
```

Groovy DSL:

```gradle
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.zearplay:FileDownloader:1.7.11-anrfix'
}
```

The JitPack build runs:

```shell
./gradlew clean :library:publishReleasePublicationToMavenLocal
```

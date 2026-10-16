# ShadowClones

A Paper plugin project assembled from the four files supplied.

## Build locally
Requirements: Java 21 and Maven.

From this folder run:

```bash
mvn clean package
```

The plugin JAR should be created at `target/ShadowClones-1.1.0.jar`.

## Build on GitHub
1. Create a GitHub repository.
2. Upload the contents of this folder (including `pom.xml`, `src/`, and `.github/`).
3. Open the repository's **Actions** tab and run or wait for **Build ShadowClones**.
4. Open the successful workflow run and download the **ShadowClones-plugin** artifact.
5. Put the JAR in your Paper server's `plugins` folder and restart the server.

## Important
This project uses Paper API `1.21.11` and Java 21. Test it on a backup/test server first. The build workflow will show compilation errors if any of the supplied Java code uses an API that differs from the selected Paper version.

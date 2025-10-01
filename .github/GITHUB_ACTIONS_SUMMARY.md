# GitHub Actions Setup Summary

## Overview

GitHub Actions workflows have been successfully configured for the Jena-FalkorDB Adapter project. This enables automated testing, building, and publishing to Maven Central.

## What Was Created

### 1. CI Workflow (`.github/workflows/ci.yml`)

**Purpose**: Continuous Integration - runs on every push and pull request to the main branch.

**Features**:
- **Multi-version Java Testing**: Tests against Java 11, 17, and 21 to ensure compatibility
- **FalkorDB Service**: Automatically starts FalkorDB container for integration tests
- **Build Steps**:
  - Checks out code
  - Sets up Java environment
  - Caches Maven dependencies
  - Compiles the code
  - Runs all tests
  - Packages the application
- **Artifact Management**:
  - Uploads test results
  - Uploads build artifacts (JAR files)
- **Code Quality**: Includes code quality checks

**Triggers**:
- Push to `main` branch
- Pull requests targeting `main` branch

### 2. Publish Workflow (`.github/workflows/publish.yml`)

**Purpose**: Automated publishing to Maven Central when releases are created.

**Features**:
- **Version Management**: 
  - Extracts version from git tags (e.g., `v1.0.0` → `1.0.0`)
  - Supports manual version override via workflow dispatch
- **Build Steps**:
  - Full Maven build
  - Generates source JAR
  - Generates JavaDoc JAR
  - Signs all artifacts with GPG
- **Publishing**:
  - Deploys to OSSRH/Maven Central
  - Uploads artifacts to GitHub release
- **Security**:
  - Uses GitHub secrets for credentials
  - GPG signing for artifact verification

**Triggers**:
- GitHub releases (automatic)
- Manual workflow dispatch (optional)

### 3. Updated pom.xml

**Changes Made**:
- Updated `groupId` from `com.example` to `com.falkordb`
- Added project metadata:
  - URL: https://github.com/FalkorDB/jena-falkordb-adapter
  - License: MIT License
  - Developers: FalkorDB Team
  - SCM information for GitHub repository
- Added distribution management for OSSRH:
  - Snapshot repository
  - Release repository
- Added required plugins:
  - **maven-source-plugin**: Generates source JAR (required for Maven Central)
  - **maven-javadoc-plugin**: Generates JavaDoc JAR (required for Maven Central)
  - **maven-gpg-plugin**: Signs artifacts with GPG (required for Maven Central)

### 4. Documentation

Created comprehensive documentation:
- **CICD_SETUP.md**: Complete guide for setting up GitHub secrets and publishing to Maven Central
- **Updated README.md**: 
  - Added CI/CD badges
  - Added CI/CD section explaining the workflows
  - Updated features list

## Required GitHub Secrets

Before publishing to Maven Central, you need to configure these secrets in your GitHub repository:

| Secret Name | Description |
|-------------|-------------|
| `OSSRH_USERNAME` | Your Sonatype OSSRH username |
| `OSSRH_TOKEN` | Your Sonatype OSSRH password or token |
| `GPG_PRIVATE_KEY` | Your GPG private key (ASCII-armored format) |
| `GPG_PASSPHRASE` | The passphrase for your GPG private key |

See [CICD_SETUP.md](../CICD_SETUP.md) for detailed instructions on obtaining and configuring these secrets.

## How to Use

### Running CI Tests

CI tests run automatically:
- On every push to `main`
- On every pull request to `main`

You can view test results in the **Actions** tab of your GitHub repository.

### Publishing a Release

1. **Update Version**: Remove `-SNAPSHOT` from version in `pom.xml`:
   ```xml
   <version>1.0.0</version>
   ```

2. **Commit and Push**: 
   ```bash
   git add pom.xml
   git commit -m "Release version 1.0.0"
   git push
   ```

3. **Create GitHub Release**:
   - Go to **Releases** → **Create a new release**
   - Create tag: `v1.0.0`
   - Fill in release notes
   - Click **Publish release**

4. **Automatic Publishing**:
   - The workflow will automatically build and publish to Maven Central
   - Monitor progress in the **Actions** tab

5. **Finalize on Maven Central**:
   - Log in to https://oss.sonatype.org/
   - Find your staged repository
   - Review and release to Maven Central

### Manual Publishing

You can also manually trigger publishing:
1. Go to **Actions** → **Publish to Maven Central**
2. Click **Run workflow**
3. Optionally specify a version
4. Click **Run workflow**

## Verification

To verify the setup is working:

1. **Test CI**: Create a pull request and ensure all checks pass
2. **Test Build**: Check that artifacts are built correctly
3. **Test Publishing**: Create a test release (e.g., `v0.0.1-test`) and verify the workflow runs

## Next Steps

Before publishing to Maven Central:

1. ✅ Set up OSSRH account (see CICD_SETUP.md)
2. ✅ Generate and upload GPG keys (see CICD_SETUP.md)
3. ✅ Configure GitHub secrets
4. ✅ Test with a pre-release version
5. ✅ Create your first official release

## Benefits

With this CI/CD setup, you get:

- ✅ **Automated Testing**: Every code change is automatically tested
- ✅ **Multi-version Support**: Ensures compatibility with Java 11, 17, and 21
- ✅ **Quality Assurance**: Prevents broken code from being merged
- ✅ **Easy Releases**: One-click publishing to Maven Central
- ✅ **Transparency**: Public test results and build status
- ✅ **Security**: Artifact signing ensures authenticity

## Maintenance

The workflows require minimal maintenance:
- Update Java versions as needed
- Update Maven plugin versions periodically
- Renew GPG keys before expiration
- Update OSSRH credentials if changed

## Support

For issues or questions:
- Check workflow logs in the **Actions** tab
- Review [CICD_SETUP.md](../CICD_SETUP.md) for setup guidance
- Consult Maven Central documentation: https://central.sonatype.org/

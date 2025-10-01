# CI/CD Setup Guide

This document explains how to set up the GitHub Actions workflows for continuous integration and publishing to Maven Central.

## GitHub Actions Workflows

This repository includes two GitHub Actions workflows:

### 1. CI Workflow (`.github/workflows/ci.yml`)

Runs automatically on:
- Push to `main` branch
- Pull requests to `main` branch

Features:
- Tests against Java 11, 17, and 21
- Runs against FalkorDB service container
- Executes full build and test suite
- Uploads test results and build artifacts
- Performs code quality checks

### 2. Publish Workflow (`.github/workflows/publish.yml`)

Runs on:
- GitHub releases
- Manual workflow dispatch

Features:
- Builds and packages the library
- Signs artifacts with GPG
- Publishes to Maven Central (OSSRH)
- Uploads artifacts to GitHub release

## Required GitHub Secrets

To enable the publish workflow, you need to configure the following secrets in your GitHub repository:

### Maven Central Credentials

1. **OSSRH_USERNAME**: Your Sonatype OSSRH username
   - Get this from [https://oss.sonatype.org/](https://oss.sonatype.org/)
   - You'll need to create a JIRA account and request access to the `com.falkordb` groupId

2. **OSSRH_TOKEN**: Your Sonatype OSSRH password or token
   - Use your OSSRH password or generate a user token

### GPG Signing

3. **GPG_PRIVATE_KEY**: Your GPG private key in ASCII-armored format
   - Generate a GPG key pair:
     ```bash
     gpg --gen-key
     ```
   - Export the private key:
     ```bash
     gpg --armor --export-secret-keys YOUR_KEY_ID > private-key.asc
     ```
   - Copy the entire content including `-----BEGIN PGP PRIVATE KEY BLOCK-----` and `-----END PGP PRIVATE KEY BLOCK-----`

4. **GPG_PASSPHRASE**: The passphrase for your GPG private key
   - This is the passphrase you set when creating the GPG key

## Setting Up GitHub Secrets

1. Go to your GitHub repository
2. Navigate to **Settings** → **Secrets and variables** → **Actions**
3. Click **New repository secret**
4. Add each of the four secrets mentioned above

## Publishing to Maven Central

### Prerequisites

1. **Sonatype OSSRH Account**:
   - Create a JIRA account at [https://issues.sonatype.org/](https://issues.sonatype.org/)
   - Create a ticket requesting access to the `com.falkordb` groupId
   - Reference: [OSSRH Guide](https://central.sonatype.org/publish/publish-guide/)

2. **GPG Key**:
   - Generate a GPG key pair (see above)
   - Upload your public key to a key server:
     ```bash
     gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
     ```

### Creating a Release

1. Update the version in `pom.xml` to remove `-SNAPSHOT`:
   ```xml
   <version>1.0.0</version>
   ```

2. Commit and push the changes

3. Create a GitHub release:
   - Go to **Releases** → **Create a new release**
   - Create a tag (e.g., `v1.0.0`)
   - Fill in release notes
   - Publish the release

4. The publish workflow will automatically:
   - Build the project
   - Sign the artifacts
   - Deploy to Maven Central staging repository
   - Upload artifacts to the GitHub release

5. Log in to [https://oss.sonatype.org/](https://oss.sonatype.org/) to:
   - Review the staged artifacts
   - Release them to Maven Central

### Manual Deployment

You can also trigger the publish workflow manually:
1. Go to **Actions** → **Publish to Maven Central**
2. Click **Run workflow**
3. Select the branch
4. Optionally specify a version
5. Click **Run workflow**

## Testing the Setup

Before creating a real release, you can test the setup:

1. Create a test tag:
   ```bash
   git tag v0.0.1-test
   git push origin v0.0.1-test
   ```

2. Create a release from this tag

3. Monitor the workflow execution

4. Check the OSSRH staging repository

5. Drop the staged artifacts (don't release to Maven Central)

## Troubleshooting

### Build Fails
- Check the CI workflow logs
- Ensure all tests pass locally: `mvn clean test`
- Verify FalkorDB connectivity

### Publishing Fails
- Verify all secrets are correctly configured
- Check GPG key format and passphrase
- Ensure OSSRH credentials are correct
- Review the publish workflow logs

### Signing Fails
- Verify GPG_PRIVATE_KEY includes headers and footers
- Check GPG_PASSPHRASE is correct
- Ensure the GPG key hasn't expired

## References

- [Maven Central Publishing Guide](https://central.sonatype.org/publish/publish-guide/)
- [GitHub Actions Documentation](https://docs.github.com/en/actions)
- [GPG Key Generation Guide](https://central.sonatype.org/publish/requirements/gpg/)

# Building Cream from Source

## Prerequisites

- [Babashka](https://babashka.org) (`bb`)
- Maven (for installing the Clojure fork)
- GraalVM EA with RuntimeClassLoading support

## 1. Install the custom Clojure fork

```sh
git clone -b crema --depth 1 https://github.com/borkdude/clojure.git /tmp/clojure-fork
cd /tmp/clojure-fork && mvn install -Dmaven.test.skip=true
```

## 2. Download GraalVM EA

EA builds with RuntimeClassLoading are published at
[graalvm/oracle-graalvm-ea-builds](https://github.com/graalvm/oracle-graalvm-ea-builds/releases).
Look for `jdk-25e1-*` releases.

List available releases:

```sh
gh release list --repo graalvm/oracle-graalvm-ea-builds --limit 5
```

Download and extract (macOS aarch64 example):

```sh
# Pick the latest jdk-25e1 tag from the list above, e.g. jdk-25e1-25.0.2-ea.16
TAG=jdk-25e1-25.0.2-ea.16
VERSION=25.0.2-ea.16

cd ~/Downloads
gh release download "$TAG" \
  --repo graalvm/oracle-graalvm-ea-builds \
  --pattern "graalvm-jdk-25e1-${VERSION}_macos-aarch64_bin.tar.gz"
tar xzf "graalvm-jdk-25e1-${VERSION}_macos-aarch64_bin.tar.gz"
```

The tarball extracts to a directory like `graalvm-25.1.0-dev+10.1/` (the
directory name does not match the tarball name). Find it with:

```sh
ls -ltr ~/Downloads/ | grep graalvm | tail -3
```

For Linux x86_64, replace `macos-aarch64` with `linux-amd64` in the pattern
and the extracted layout has no `Contents/Home` prefix.

Verify:

```sh
# macOS
~/Downloads/graalvm-25.1.0-dev+10.1/Contents/Home/bin/native-image --version

# Linux
~/Downloads/graalvm-25.1.0-dev+10.1/bin/native-image --version
```

## 3. Build the native binary

```sh
# macOS
GRAALVM_HOME=~/Downloads/graalvm-25.1.0-dev+10.1/Contents/Home bb build-native

# Linux
GRAALVM_HOME=~/Downloads/graalvm-25.1.0-dev+10.1 bb build-native
```

This builds the uberjar first, then runs `native-image` with Crema flags.
The output binary is `./cream` in the project root.

## Identifying the GraalVM commit

Each EA release is built from a specific `oracle/graal` commit. Two ways to find it:

1. **From the release page**: each release at
   [graalvm/oracle-graalvm-ea-builds](https://github.com/graalvm/oracle-graalvm-ea-builds/releases)
   has at the bottom: `Based on GraalVM commit: oracle/graal@<sha>`

2. **From the `release` file** inside the extracted GraalVM directory:
   ```sh
   # macOS
   cat ~/Downloads/graalvm-25.1.0-dev+10.1/Contents/Home/release | grep compiler
   # Look for commit.rev in the SOURCE or COMMIT_INFO fields
   ```

This is useful for checking whether a specific fix or PR has landed in a given
EA build:

```sh
# Get the EA commit
EA_COMMIT=bb17fd7e8ec441c087b63300c2d75e06828b8dde

# Get the merge commit of a PR
FIX_COMMIT=$(gh pr view 13081 --repo oracle/graal --json mergeCommit --jq '.mergeCommit.oid')

# Check if the fix is included ("ahead" or "identical" = included, "behind" = not included)
gh api "repos/oracle/graal/compare/${FIX_COMMIT}...${EA_COMMIT}" --jq '.status'
```

## Other build tasks

```sh
bb uber           # Build uberjar only
bb clean          # Clean target directory
bb gen-reflect    # Regenerate reflect-config.json
bb run-lib-tests  # Run library tests against the cream binary
```

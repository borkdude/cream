# Building Cream from Source

## Prerequisites

- [Babashka](https://babashka.org) (`bb`)
- Maven (for installing the Clojure fork)
- GraalVM EA with RuntimeClassLoading support, currently
  `jdk-25i2-25.0.3-ea.04`

## 1. Install the custom Clojure fork

```sh
git clone -b crema --depth 1 https://github.com/borkdude/clojure.git /tmp/clojure-fork
cd /tmp/clojure-fork && mvn install -Dmaven.test.skip=true
```

## 2. Download GraalVM EA

Builds are pinned to `jdk-25i2-25.0.3-ea.04`, the release that fixes the
vthread memory corruption of
[GH-13925](https://github.com/oracle/graal/issues/13925). `GRAALVM_EA_TAG` in
[.github/workflows/main.yml](../.github/workflows/main.yml) is the source of
truth. Older `jdk-25e1-*` releases segfault in the core.async tests.

EA builds with RuntimeClassLoading are published at
[graalvm/oracle-graalvm-ea-builds](https://github.com/graalvm/oracle-graalvm-ea-builds/releases).
List available releases:

```sh
gh release list --repo graalvm/oracle-graalvm-ea-builds --limit 5
```

Download and extract (macOS aarch64 example):

```sh
TAG=jdk-25i2-25.0.3-ea.04

cd ~/Downloads
gh release download "$TAG" \
  --repo graalvm/oracle-graalvm-ea-builds \
  --pattern "graalvm-jdk-25i2-25.0.3-ea.04_macos-aarch64_bin.tar.gz"
tar xzf graalvm-jdk-25i2-25.0.3-ea.04_macos-aarch64_bin.tar.gz
```

The extracted directory name does not match the tarball name and repeats
across EA releases, so successive extracts overwrite each other. Rename it
after the release, so multiple builds can coexist and `GRAALVM_HOME` is
unambiguous:

```sh
mv <extracted-dir> graalvm-jdk-25i2-25.0.3-ea.04
ln -sfn graalvm-jdk-25i2-25.0.3-ea.04 graalvm-cream
```

The `graalvm-cream` symlink marks which of the GraalVMs in `~/Downloads` cream
is built with.

For Linux x86_64, replace `macos-aarch64` with `linux-x64` in the pattern
and the extracted layout has no `Contents/Home` prefix.

Verify:

```sh
# macOS
~/Downloads/graalvm-cream/Contents/Home/bin/native-image --version

# Linux
~/Downloads/graalvm-cream/bin/native-image --version
```

## 3. Build the native binary

```sh
# macOS
GRAALVM_HOME=~/Downloads/graalvm-cream/Contents/Home bb build-native

# Linux
GRAALVM_HOME=~/Downloads/graalvm-cream bb build-native
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
   cat ~/Downloads/graalvm-cream/Contents/Home/release | grep compiler
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

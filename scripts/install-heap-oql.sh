#!/bin/bash
#
# install-heap-oql.sh — Builds and installs the heap-oql plugin into Eclipse MAT.
#
# Usage: install-heap-oql.sh <MAT_DIR> <SKILL_DIR>
#   MAT_DIR:   Path to Eclipse MAT installation (contains MemoryAnalyzer binary)
#   SKILL_DIR: Path to this skill's root directory (contains references/)
#
set -euo pipefail

MAT_DIR="${1:?Usage: install-heap-oql.sh <MAT_DIR> <SKILL_DIR>}"
SKILL_DIR="${2:?Usage: install-heap-oql.sh <MAT_DIR> <SKILL_DIR>}"

echo "=== Installing heap-oql plugin into $MAT_DIR ==="

# 1. Create source tree
mkdir -p "$MAT_DIR/plugins/heap-oql/src/org/heapoql"
mkdir -p "$MAT_DIR/plugins/heap-oql/META-INF"
mkdir -p "$MAT_DIR/plugins/heap-oql/bin"

# 2. Copy source files from skill references
cp "$SKILL_DIR/references/HeapOQLApp.java" "$MAT_DIR/plugins/heap-oql/src/org/heapoql/"
cp "$SKILL_DIR/references/plugin.xml" "$MAT_DIR/plugins/heap-oql/"
cp "$SKILL_DIR/references/MANIFEST.MF" "$MAT_DIR/plugins/heap-oql/META-INF/"

# 3. Compile
echo "Compiling..."
javac -d "$MAT_DIR/plugins/heap-oql/bin" \
  -cp "$(echo "$MAT_DIR"/plugins/org.eclipse.mat.api_*.jar):$(echo "$MAT_DIR"/plugins/org.eclipse.mat.report_*.jar):$(echo "$MAT_DIR"/plugins/org.eclipse.equinox.app_*.jar):$(echo "$MAT_DIR"/plugins/org.eclipse.equinox.common_*.jar):$(echo "$MAT_DIR"/plugins/org.eclipse.osgi_*.jar):$(echo "$MAT_DIR"/plugins/org.eclipse.core.runtime_*.jar)" \
  "$MAT_DIR/plugins/heap-oql/src/org/heapoql/HeapOQLApp.java"

# 4. Package JAR
echo "Packaging..."
(cd "$MAT_DIR/plugins/heap-oql" && jar cfm ../org.heapoql_1.0.0.jar META-INF/MANIFEST.MF -C bin . plugin.xml)

# 5. Register bundle in OSGi (if not already registered)
BUNDLES_INFO="$MAT_DIR/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
if ! grep -q "org.heapoql" "$BUNDLES_INFO" 2>/dev/null; then
  echo "org.heapoql,1.0.0,plugins/org.heapoql_1.0.0.jar,4,false" >> "$BUNDLES_INFO"
  echo "Registered bundle in bundles.info"
fi

# 6. Create wrapper script
cat > "$MAT_DIR/heap-oql" << 'WRAPPER'
#!/bin/sh
MAT_DIR="$(dirname -- "$0")"
exec "$MAT_DIR/MemoryAnalyzer" -consolelog -nosplash -application org.heapoql.app "$@" 2>/tmp/heap-oql-stderr.log
WRAPPER
chmod +x "$MAT_DIR/heap-oql"

# 7. Force OSGi to discover new bundle (one-time)
echo "Running -clean to register OSGi bundle..."
"$MAT_DIR/MemoryAnalyzer" -clean -consolelog -nosplash -application org.heapoql.app 2>&1 | head -3 || true

echo "=== heap-oql installed successfully ==="
echo "Usage: $MAT_DIR/heap-oql <dump_file> <mode> [args...]"
echo "Run '$MAT_DIR/heap-oql' without args for help."

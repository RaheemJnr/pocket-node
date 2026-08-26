#!/usr/bin/env bash
# Downloads pinned graph libraries once. Run manually; the output is committed
# so the tool itself never needs network access.
set -euo pipefail
cd "$(dirname "$0")"
fetch() { echo "fetching $1"; curl -fsSL "$2" -o "$1"; }

fetch cytoscape.min.js   https://unpkg.com/cytoscape@3.30.2/dist/cytoscape.min.js
fetch dagre.min.js       https://unpkg.com/dagre@0.8.5/dist/dagre.min.js
fetch cytoscape-dagre.js https://unpkg.com/cytoscape-dagre@2.5.0/cytoscape-dagre.js
fetch layout-base.js     https://unpkg.com/layout-base@2.0.1/layout-base.js
fetch cose-base.js       https://unpkg.com/cose-base@2.2.0/cose-base.js
fetch cytoscape-fcose.js https://unpkg.com/cytoscape-fcose@2.2.0/cytoscape-fcose.js

shasum -a 256 *.js | tee SHA256SUMS
echo "Review SHA256SUMS, then commit both the .js files and the sums."

#!/bin/sh
# Rebuilds android/app/src/main/assets/public/tailwind.css from the classes used in index.html.
# Run after adding Tailwind classes (the app no longer loads the Tailwind runtime from the web).
cd "$(dirname "$0")/../android/app/src/main/assets/public" || exit 1
printf '@tailwind base;\n@tailwind components;\n@tailwind utilities;\n' > /tmp/jindo-tw-in.css
npx -y tailwindcss@3.4.17 -i /tmp/jindo-tw-in.css -o tailwind.css --content index.html --minify
# dist/ is what npx cap sync ships: keep it in step with the app copy
cp index.html esde-presets.js tailwind.css ../../../../../../dist/

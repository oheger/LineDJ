#!/bin/sh

# Example script to create a custom Java runtime image on which LineDJ
# applications can be executed. The script expects the following input
# parameters:
# - The version of JavaFX to be downloaded, e.g. "17.0.2"
# - The platform, for which to download, e.g. "linux-x64"
# - The target path where to create the custom image.
#
# The script downloads the selected JavaFX jmod files and uses JLink to create
# the custom image in a subfolder of the specified target folder named
# jdkfx-<VERSION>.

if [ "$#" -ne 3 ]; then
  echo "Usage: $0 <Java FX version> <target path>"
  exit 1
fi

VERSION=$1
PLATFORM=$2
TARGET=$3

TEMP_DIR=$(mktemp -d)

cleanUp() {
    rm -rf "$TEMP_DIR"
}

trap cleanUp EXIT

wget -P "$TEMP_DIR" https://download2.gluonhq.com/openjfx/"$VERSION"/openjfx-"${VERSION}"_"$PLATFORM"_bin-jmods.zip
unzip -o -d "$TEMP_DIR" "$TEMP_DIR"/openjfx-"${VERSION}"_"$PLATFORM"_bin-jmods.zip

export PATH_TO_FX_MODS=$TEMP_DIR/javafx-jmods-$VERSION

"$JAVA_HOME"/bin/jlink --module-path "$PATH_TO_FX_MODS" \
  --add-modules java.base,java.desktop,java.logging,java.management,java.naming,java.sql,java.xml,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.jdwp.agent,jdk.jfr,jdk.unsupported,javafx.controls \
  --output "$TARGET"/jdkfx-"$VERSION"

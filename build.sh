#!/usr/bin/env sh
GIT_VERSION=$(git describe --abbrev --dirty --always --tags)


PLUGIN=Cell_Track_and_Measure
BUILD_DIR=./build
SRC_DIR=./src
SRC_LIBS=./libs/ij:./libs/cmwt

[ -d ${BUILD_DIR} ] && find ${BUILD_DIR} -name '*.class' -delete || mkdir -p ${BUILD_DIR}

javac -Xlint:none -proc:none -cp ${SRC_LIBS} -d ${BUILD_DIR} ${SRC_DIR}/${PLUGIN}.java

[ $? -eq 0 ] && jar cf ${BUILD_DIR}/${PLUGIN}_${GIT_VERSION}.jar -C ${BUILD_DIR}/ ${PLUGIN}.class

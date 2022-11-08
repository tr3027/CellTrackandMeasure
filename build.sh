#!/bin/bash

rm -rf build/* && \
javac -Xlint:none -proc:none -cp ./libs/ij/:./libs/cmwt/ -d build/ src/Cell_Track_and_Measure.java && \
jar cf build/Cell_Track_and_Measure.jar -C build/ Cell_Track_and_Measure.class && \
cp build/Cell_Track_and_Measure.jar ~/bin/Fiji.app/plugins/

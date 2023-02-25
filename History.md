# Release notes of the Cell_Track_and_Measure plugin for ImageJ

## v0.2 - 2023.01.01
- best match used instead of last match when no ideal match was found in frame (Issue #1)
- added graphing of the measured data (Issue #3)
- plugin preferences now completely saved into/restored from ImageJ config file
- tracking channel will change each time an image is switched
- ROIs won't delete anymore when another image is selected as even plots are images
- automatic exporting of measurement data to CSV to the same path as the original file
  (origfile.ext -> origfile_date_time.csv)
- resolved some issues causing exceptions including Issue #5 
- configuration of graph outputs (Issue #4)
- fixed a NullPointerException (Issue #6)
 
## v0.1 (initial release) - 2022.11.22
- cells correctly detected within image (Issue #1)
- tracking channel selectable
- multiple ROIs can be created by Shift+Click
- cell tracking works acceptably well
- tracked ROIs can be draw in overlay in all channels and each frame (Issue #4)
- tracked ROIs can generate measurements table, here the math needs to get double checked


# fs2CyborgPreprocessing
Real-time preprocessing of raw electrode voltage stream, using functional streams 2 (fs2) in Scala.

* Example 1 converts an Int stream of pV of an electrode into noise reduced PSD stream in the range 296 - 3000 Hz . Heavy preprocessed signal.

* Example 2 converts an Int stream of pV of an electrode into a 6 second 80 % overlapping sliding window count of action potentials in individual frequency bins from Power Spectral Densities (PSDs) in the range 296 - 3000 Hz . Lightweight preprocessed signal, good for PCA models.

Opposed to Example 1, the preprocessed data in Example 2 is lightweight. One 7 minute raw electrode is estimated to take up 2.2 GB / 60 ~= 40 MB storage space. The preprocessed PSDs in Example 1 took 105.5 MB, while in Example 2 only 476.7 KB .

Both examples rely on pre-computed noise tresholds for the selected electrode (from other code). It assumes 10000 Hz sampling rate on the raw electrode voltage stream.
Computation can be speeded up, or throttled to real-time speed when reading data from a file. It can either save the preprocessed stream to a file, or send it further over internet via a TCP socket server.
The code should be straight forward to implement in [SHODAN](https://github.com/PeterAaser/SHODAN) for real-time preprocessing
in the Cyborg project.

Audacity, Python matplotlib and The Unscrambler X was used for the plots in the example.

## Dependencies:

* Forked scala signal processing library [scalasignal](https://github.com/ivartz/scalasignal). Prebuilt jar in lib folder.
* The rest of the dependencies should be pulled automaticly with scala build tool (sbt) based on the build.sbt file.

## How to run:
1. Make shure scala and scala build tool (sbt) is installed. Linux is preferred.
2. Clone the repository
```bash
git clone https://github.com/ivartz/fs2CyborgPreprocessing
```
3. Go to the folder
```bash
cd fs2CyborgPreprocessing
```
3. Make shure all file paths are adjusted correctly in Main.scala .
   Also make shure the relevant preprocessing operations is selected in the code (commented/uncommented).
   The preprocessing steps Main.scala are well documented in comments. The first lines of the code are preprequisites for multithreading: Thread pool factory.
4. Run the program
```bash
sbt run
```
## Example 1: Complete noise reduced PSD stream.

1. Select electrode 87 from MEA2 Dopey experiment #2 (2017-03-20), based on offline analysis in Python. Raw data is converted to audio for visualization (not this code).
![2 87 raw audio audacity](/img/2_87_raw_audio_audacity.png
)
2. Extract noise segments, based on PC1 score from PCA of #2 raw data (not this code).
![2 87 noise segments extracted](/img/2_87_noise_segments_extracted.png)

3. Construct noise tresholds for the PSD to be used in the real-time preprocessing. Only the frequency range 296 - 3000 Hz is used later (not this code).
![2 87 max PSD from noise segments](/img/2_87_max_PSD_from_noise_segments.png)

4. Compute PSDs in intermediate steps. Line plot of a matrix of computed PSDs along the time axis is shown for completeness of the example.
![2 87 unfiltered PSDs](/img/2_87_unfiltered_PSDs.png)

5. Use noise tresholds to construct varying decibel attenuation gains over time. Max attenuation is fixed, and set to -48 db in this example. Smooth varying attenuation gains is enshured by using a 4. order IIR Butterworth filter with cutoff 6 Hz .
![2 87 AP detection low pass filtered from PSDs](/img/2_87_AP_detection_low_pass_filtered_from_PSDs.png)

6. Attenuate the raw PSDs with the attenuation gains, resulting in a 40 Hz preprocessed PSD stream.
![2 87 48dbfiltered PSDs.png](/img/2_87_48dbfiltered_PSDs.png)


## Example 2: Remebering treshold exceeds for last 6 seconds, outputting in intervals of 1.2 seconds. Builds varying frequency-to-above-treshold-counts shapes that is proposed to be good for PCA models. It is an attempt to convert sliding window history of action potentials (timing information) to amplitude information in aggregated shapes. It is proposed that multivariate analysis on this preprocessed data, such as PCA with its linearly independent basis tranformation, can capture timing patterns for the last 6 seconds in action potentials across different frequencies in this way.

1. Select electrode 87 from MEA2 Dopey experiment #2 (2017-03-20), based on offline analysis in Python. Raw data is converted to audio for visualization (not this code).
![2 87 raw audio audacity](/img/2_87_raw_audio_audacity.png)

2. Extract noise segments, based on PC1 score from PCA of #2 raw data (not this code).
![2 87 noise segments extracted](/img/2_87_noise_segments_extracted.png)

3. Construct noise tresholds for the PSD to be used in the real-time preprocessing. Only the frequency range 296 - 3000 Hz is used later (not this code).
![2 87 max PSD from noise segments](/img/2_87_max_PSD_from_noise_segments.png)

4. Compute PSDs in intermediate steps. Line plot of a matrix of computed PSDs along the time axis is shown for completeness of the example.
![2 87 unfiltered PSDs](/img/2_87_unfiltered_PSDs.png)

5. Use noise tresholds to count each time an amplitude in the raw PSD exceeds the trehold in a given bin. This is done in a sliding window of 6 seconds, with 80 % overlap. This results in a stream of aggregated counts each 1.2 seconds (0.83 Hz stream).
![2 87 AP detection low pass filtered from PSDs](/img/2_87_AP_detection_from_PSDs_sliding_6s_windows.png)

![2 87 AP detection low pass filtered from PSDs 3D](/img/2_87_AP_detection_from_PSDs_sliding_6s_windows_3D.png)


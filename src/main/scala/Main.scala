object Main {
    def main(args: Array[String]) {
        // make backend implicits for asynchrony.
        import fs2.internal.NonFatal
        import java.nio.channels.AsynchronousChannelGroup
        import scala.concurrent.ExecutionContext
        object backendImplicits {

            import fs2._
            import java.util.concurrent.Executors

            implicit val tcpACG: AsynchronousChannelGroup = namedACG.namedACG("tcp")
            //                                                                                                        hehe
            implicit val Sch: Scheduler = Scheduler.fromScheduledExecutorService(
                Executors.newScheduledThreadPool(
                    16, threadFactoryFactoryProxyBeanFactory.mkThreadFactory("scheduler", daemon = true)
                )
            )
        }
        object namedACG {
            // Lifted verbatim from fs2 tests.
            import java.nio.channels.AsynchronousChannelGroup
            import java.lang.Thread.UncaughtExceptionHandler
            import java.nio.channels.spi.AsynchronousChannelProvider
            import java.util.concurrent.ThreadFactory
            import java.util.concurrent.atomic.AtomicInteger

            def namedACG(name: String): AsynchronousChannelGroup = {
                val idx = new AtomicInteger(0)
                AsynchronousChannelProvider.provider().openAsynchronousChannelGroup(
                    16
                    , new ThreadFactory {
                        def newThread(r: Runnable): Thread = {
                            val t = new Thread(r, s"fs2-AG-$name-${idx.incrementAndGet()}")
                            t.setDaemon(true)
                            t.setUncaughtExceptionHandler(
                                new UncaughtExceptionHandler {
                                    def uncaughtException(t: Thread, e: Throwable): Unit = {
                                        println("----------- UNHANDLED EXCEPTION ---------")
                                        e.printStackTrace()
                                    }
                                }
                            )
                            t
                        }
                    }
                )
            }
        }
        object threadFactoryFactoryProxyBeanFactory {

            import java.lang.Thread.UncaughtExceptionHandler
            import java.util.concurrent.{Executors, ThreadFactory}
            import java.util.concurrent.atomic.AtomicInteger

            def mkThreadFactory(name: String, daemon: Boolean, exitJvmOnFatalError: Boolean = true): ThreadFactory = {
                new ThreadFactory {
                    val idx = new AtomicInteger(0)
                    val defaultFactory = Executors.defaultThreadFactory()

                    def newThread(r: Runnable): Thread = {
                        val t = defaultFactory.newThread(r)
                        t.setName(s"$name-${idx.incrementAndGet()}")
                        t.setDaemon(daemon)
                        t.setUncaughtExceptionHandler(new UncaughtExceptionHandler {
                            def uncaughtException(t: Thread, e: Throwable): Unit = {
                                ExecutionContext.defaultReporter(e)
                                if (exitJvmOnFatalError) {
                                    e match {
                                        case NonFatal(_) => ()
                                        case fatal => System.exit(-1)
                                    }
                                }
                            }
                        }
                        )
                        t
                    }
                }
            }
        }
        import backendImplicits._
        import cats.effect._
        import cats.effect.{IO, Sync}
        import fs2.{io, text, Stream}
        import fs2._
        import fs2.io.tcp._
        import java.nio.file.{Path, Paths}
        import scala.concurrent.ExecutionContext
        import scala.concurrent.duration._
        import scala.concurrent.ExecutionContext.Implicits.global
        import java.net.InetSocketAddress
        import signal.PSD._
        import scala.collection.immutable.Seq
        // For Second-Order-Section IIR Butterworth low pass filter
        import signal.Butter._
        import signal.SOSFilt._
        // For db attenuation
        import scala.math.pow
        // for the smoothed z-score algorithm for spike detection of low pass filtered PSDs
//        import org.apache.commons.math3.stat.descriptive.SummaryStatistics
//        import scala.collection.mutable.ArrayBuffer

        // Choose source file for streaming.
        val fileToStream = Paths.get("/home/jovyan/work/Master thesis/data/2_MEA2_raw.csv")
        // Close electrode ID from to stream.
        // , - separated columns fileToStream
        // should be ordered with increasing electrode ID.
        // An electrode ID is mapped to another number as well,
        // identifying the electrode in f.ex. MultiChannel Experimeter.
        // For instance, electrode ID 50 is electrode 87 .
        // This mapping should be fixed, or at least agreed upon
        // before analysis.
        val selected_electrode_id = 50
        // Name folder for experiment.
        val experimentName = "2017-03-20T10-02-16 (#2)"
        // Specify and create output file path
        val outputFilePath = "/home/jovyan/work/Master thesis/data/Preprocessed/fs2/" + experimentName
        new java.io.File(outputFilePath).mkdirs
        // Choose file path and name to save the preprocessed data
        val outputFilePathAndName = outputFilePath + "/" + s"ID=$selected_electrode_id.csv"
        // Sampling rate in source file
        val Fs = 10000
        /// Port to send the processed data.
        val TCPPort = 54321
        // Low pass filter parameters. The low pass filter is applied on sequences of frequency bins
        // from PSDs .
        // The PSD is computed from a 1250 length window.
        // The window overlaps with 80 %, or 1000 samples.
        // This results in a PSD stream of 40 Hz .
        val PSDStreamSamplingRate = 40
        // The action potentials are detected individually
        // in each frequency bin. The are verified experimentally
        // to occur in synchronizations of not faster than
        // approximately 6 Hz .
        // The low pass filter used on the noise reduction
        // is thus with cut-off 6 Hz .
        val PSDStreamCutoffFrequency = 6.0
        val normalizedCutoff = PSDStreamCutoffFrequency / (PSDStreamSamplingRate / 2)
        // From Matlab's fdatool, filter designer tool,
        // an IIR 4. order Butterworth filter has proven to
        // applicable in this type of data.
        // This was done by making syntetic burst data,
        // where each burst durated 6 seconds, and
        // the rate of action potentials (APs) firing within
        // a burst was ~ 3 Hz (a single AP contaned 12 samples, with 40 Hz sampling rate).
        // Approximately 12 samples are assumed to be
        // at least the average firing rate of APs within a burst .
        val butterFilterCoeffs = butterSOSEven(4, normalizedCutoff)
        // Pre-computed noise tresholds used in noiseReduceBinsWithTresholds .
        // The noise tresholds was computed in this way:

        // 1. Select a MEA experiment where you think you can
        // find good segments of noise, between clearly
        // visible synchronized bursts (even in the raw timeseries data,
        // at least on data from some of the electrodes).
        // Preferably a young culture, since visible synchronized bursts happen
        // more often in such a case (?, experimental).
        // Experiment #2 was used initially in the Master thesis.
        //
        // 2. Make one large PCA model of the raw timeseries data.
        // PC1 score should clearly capture the bursts for the
        // procedure to work.
        // Save PC1 score to disk. Code: "SVD #2".ipynb (Scala, Apache Spark) .
        //
        // 3. Another script extracts the noise segments from the PC1, score,
        // along with an index where the noise sample was found in the PC1 score vector.
        // The idea is to later use these indices to extract
        // per-electrode noise segments from the earlier selected experiment (#2).
        // The script uses a simple treshold ratio to detect where it is inside
        // an outside of a burst, hence finding out where noise segments start
        // and end between bursts. The extracted noise segments
        // are then saved to file along with the index
        // of where it found any noise sample in the original PC1 score.
        // NB: It is important to note that the length of PC1 score
        // is the same as the length of any raw electrode data in #2 .
        // This is why the indices can be used to find exact noise
        // profiles for any electrode based on raw timeseries data
        // from selected experiment (#2 here).
        // Save noise segments to file.
        // Code: "noise segments from #2 PC1 score".ipynb (Scala).
        //
        // 4. Another two scripts uses the indices of noise segments
        // to extract the noise segments for a selected electrode (script 1)
        // and create an average 1250 segment 80% sliding window
        // based PSD based on the noise segments for the selected electrode (script 2).
        // Code: "noise segments from an electrode based on #2 PC1 score".ipynb (Scala)(script 1),
        // "PSD tresholds from noise segment of an electrode (Python)".ipynb (Python)(script 2)
        // The last script (2) saves the final noise tresholds for the selected electrode
        // that is to be imported into this program from the path noiseTresholds87Path.
        val noiseTresholds87Path = "/home/jovyan/work/Master thesis/scripts/fs2CyborgPreprocessing/noiseTresholds/MEA2 Dopey 2017-03-20 (#2)/Max noise PSDs/87 (ID=50) [pV]_max_PSD.csv"
        // Start and end index of streamed PSD
        // based on the selected freqiency range 296 - 3000 Hz .
        val startFreq = 296 / 8
        val endFreq = 3000 / 8
        // Specify the degree of attenuation of PSD bins that fall below
        // the noise treshold [db]
        //val noiseAttenuationdb = -20D
        //val noiseAttenuationdb = 0D // not any filtering
        val noiseAttenuationdb = -48D
        // Slice the noise tresholds to the wished frequency range.
        val noiseTresholds87Sliced = scala.io.Source.fromFile(noiseTresholds87Path)
          .mkString.split("\n")
          .map(_.toDouble)
          .to[scala.collection.immutable.Seq]
          .slice(startFreq, endFreq + 1)
//        // Parameters for smoothed z-score algorithm for spike detection
//        // of low pass filtered PSDs coming from noiseReduce.
//        val spikeDetectionFilterLag = 6 // window is 6/40 = 0.15 second ~ 1/6 ~ 0.16 second, which is assumed to be the fastest oscillation
//        // that should be regarded as a spike / action potential.
//        val spikeDetectionFilterThreshold = 80d // This seems to be a reasonable treshold with -48 db attenuated signals from the noiseReduce
//        // in the preprocessing. Peak detecded if standard moving standard deviation suddenly becomes 1000
//        // times larger.
//        val spikeDetectionFilterInfluence = 1d // Maximize the influence a neighboring point has on the moving standard deviation calculation,
//        // in order to not detect too many peaks within an action potential.

        def noiseReduceBinsWithTresholds(buffer: Seq[Seq[Double]], tresholds: Seq[Double], attenuation: Double): Seq[Seq[Double]] = {
            // Low pass filter each 15 sample long
            // spike (in action potential)
            // detection based on predefined tresholds.
            // Then, the low pass filtered bin spike
            // detections are used to attuneate the
            // actual unfiltered signal.
            // only the middle 5 samples are returned.
            // This is an attempt to have piece wise
            // smooth filtering by including 5
            // non-filtered samples
            // in both past and future and
            // hence having smooth transitions
            // between the filtered output of
            // this function.

            // Important assumption:
            // buffer.length == 15
            buffer.transpose.zipWithIndex.map { case (binSequence: Seq[Double], index: Int) =>
                val currentTreshold = tresholds.apply(index)
                val attenuationGains = binSequence.map { v: Double =>
                    if (v > currentTreshold) {
                        // do not attenuate
                        1D
                    }
                    else {
                        // attenuate
                        0D
                    }
                }
                val lowPassFilteredAttenuationGains = sosfilt(butterFilterCoeffs, attenuationGains)
                ///*
                binSequence.zip(lowPassFilteredAttenuationGains).map { case (p: Double, aGain: Double) => {
                    // Sxx_out = Sxx_in (this is p) * 10 ^ ( - (1-attenuationGain [0 t0 1])*(attenuation [db]/10) )
                    p * pow(10, (1D - aGain) * attenuation / 10)
                }
                }.slice(5 - 1, 10 - 1) // Only take the middle 5 Seq[Double] from the Seq[Seq[Double]]
                                       // to make shure that the initial state of the low pass filter
                                       // of 0D is not affecting the filtered signal.
                                       // Data in first and last 5 Seq[Double] are not forgotten
                                       // because of the 10 Seq[Double] overlap in the incoming
                                       // buffer: Seq[Seq[Double]]
            }.transpose
        }

        def detectActionPotentialsInBinsWithTresholdsAndLowPassFilter(buffer: Seq[Seq[Double]], tresholds: Seq[Double], attenuation: Double): Seq[Seq[Double]] = {
            // Important assumption:
            // buffer.length == 15
            buffer.transpose.zipWithIndex.map { case (binSequence: Seq[Double], index: Int) =>
                val currentTreshold = tresholds.apply(index)
                val spikeDetected = binSequence.map { v: Double =>
                    if (v > currentTreshold) {
                        // do not attenuate
                        1D
                    }
                    else {
                        // attenuate
                        0D
                    }
                }
                val lowPassFilteredSpikeDetected = sosfilt(butterFilterCoeffs, spikeDetected)
                lowPassFilteredSpikeDetected.slice(5 - 1, 10 - 1)
            }.transpose
        }

        def detectActionPotentialsInBinsWithTresholds(buffer: Seq[Seq[Double]], tresholds: Seq[Double], attenuation: Double): Seq[Seq[Double]] = {
            // Important assumption:
            // buffer.length == 15
            buffer.transpose.zipWithIndex.map { case (binSequence: Seq[Double], index: Int) =>
                val currentTreshold = tresholds.apply(index)
                val spikeDetected = binSequence.map { v: Double =>
                    if (v > currentTreshold) {
                        // do not attenuate
                        1D
                    }
                    else {
                        // attenuate
                        0D
                    }
                }
                spikeDetected.slice(5 - 1, 10 - 1)
            }.transpose
        }

        def countActionPotentialsInBins(buffer: Seq[Seq[Double]]): Seq[Seq[Double]] = {
            buffer.transpose.map { binSequence: Seq[Double] =>
                val numberOfDetectedActionPotentials = binSequence.sum
                Seq(numberOfDetectedActionPotentials)
            }.transpose
        }

//        def smoothedZScore(y: Seq[Double], lag: Int, threshold: Double, influence: Double): Seq[Int] = {
//            // Peak detection with smoothed z-score algorithm
//            // Author Mike Roberts, taken from
//            // https://stackoverflow.com/questions/22583391/peak-signal-detection-in-realtime-timeseries-data/48231877#48231877
//            /**
//              * Smoothed zero-score alogrithm shamelessly copied from https://stackoverflow.com/a/22640362/6029703
//              * Uses a rolling mean and a rolling deviation (separate) to identify peaks in a vector
//              *
//              * @param y         - The input vector to analyze
//              * @param lag       - The lag of the moving window (i.e. how big the window is)
//              * @param threshold - The z-score at which the algorithm signals (i.e. how many standard deviations away from the moving mean a peak (or signal) is)
//              * @param influence - The influence (between 0 and 1) of new signals on the mean and standard deviation (how much a peak (or signal) should affect other values near it)
//              * @return - The calculated averages (avgFilter) and deviations (stdFilter), and the signals (signals)
//              */
//            val stats = new SummaryStatistics()
//
//            // the results (peaks, 1 or -1) of our algorithm
//            val signals = ArrayBuffer.fill(y.length)(0)
//
//            // filter out the signals (peaks) from our original list (using influence arg)
//            val filteredY = y.to[ArrayBuffer]
//
//            // the current average of the rolling window
//            val avgFilter = ArrayBuffer.fill(y.length)(0d)
//
//            // the current standard deviation of the rolling window
//            val stdFilter = ArrayBuffer.fill(y.length)(0d)
//
//            // init avgFilter and stdFilter
//            y.take(lag).foreach(s => stats.addValue(s))
//
//            avgFilter(lag - 1) = stats.getMean
//            stdFilter(lag - 1) = Math.sqrt(stats.getPopulationVariance) // getStandardDeviation() uses sample variance (not what we want)
//
//            // loop input starting at end of rolling window
//            y.zipWithIndex.slice(lag, y.length - 1).foreach {
//                case (s: Double, i: Int) =>
//                    // if the distance between the current value and average is enough standard deviations (threshold) away
//                    if (Math.abs(s - avgFilter(i - 1)) > threshold * stdFilter(i - 1)) {
//                        // this is a signal (i.e. peak), determine if it is a positive or negative signal
//                        signals(i) = if (s > avgFilter(i - 1)) 1 else { println("smoothedZScore warning: negative spike detected"); -1 }
//                        // filter this signal out using influence
//                        filteredY(i) = (influence * s) + ((1 - influence) * filteredY(i - 1))
//                    } else {
//                        // ensure this signal remains a zero
//                        signals(i) = 0
//                        // ensure this value is not filtered
//                        filteredY(i) = s
//                    }
//                    // update rolling average and deviation
//                    stats.clear()
//                    filteredY.slice(i - lag, i).foreach(s => stats.addValue(s))
//                    avgFilter(i) = stats.getMean
//                    stdFilter(i) = Math.sqrt(stats.getPopulationVariance) // getStandardDeviation() uses sample variance (not what we want)
//            }
//            return signals.to[Seq]
//        }

//        def detectActionPotentialsInBins(buffer: Seq[Seq[Double]]): Seq[Seq[Double]] = {
//            buffer.transpose.map { binSequence: Seq[Double] => {
//                smoothedZScore(binSequence, spikeDetectionFilterLag, spikeDetectionFilterThreshold, spikeDetectionFilterInfluence).map(_.toDouble)
//            }
//            }.transpose
//        }

        def stridedSeqSeqSlide[F[_],I](windowWidth: Int, overlap: Int): Pipe[F,Seq[Double],Seq[Seq[Double]]] = {
            // This buffering acts similar to stridedSlide .
            require(windowWidth > 0, "windowWidth must be > 0")
            require(windowWidth > overlap, "windowWidth must be wider than overlap")
            val stepsize = windowWidth - overlap
            def go(s: Stream[F, Seq[Double]], earlier: Seq[Seq[Double]]): Pull[F, Seq[Seq[Double]], Unit] = {
                s.pull.unconsN(stepsize.toLong, false).flatMap {
                    if (earlier.length == overlap) {
                        case Some((seg, tl)) => {
                            val forced = earlier ++ seg.force.toVector //.map(_.asInstanceOf[Double]) )
                            Pull.output1(forced) >> go(tl, forced.drop(stepsize))
                        }
                        case None => {
                            Pull.done
                        }
                    }
                    else {
                        case Some((seg, tl)) => {
                            val forced = earlier ++ seg.force.toVector //.map(_.asInstanceOf[Double]) )
                            go(tl, forced)
                        }
                        case None => {
                            Pull.done
                        }
                    }
                }
            }
            in => go(in, Seq()).stream
        }

        // (implicit ec: ExecutionContext, s: Scheduler)
//        def lowPassFilterFromSeqSeq[F[_],I]: Pipe[F,Seq[Seq[Double]],Seq[Seq[Double]]] = {
//            // low pass filter each 15 sample long
//            // constructed spike (in action potential)
//            // detection based on tresholds.
//            // Then, the low pass filtered bin spike
//            // detections are used to attuneate the
//            // actual unfiltered signal.
//            // only the middle 5 samples are returned.
//            // This is an attempt to have piece wise
//            // smooth filtering by including 5
//            // non-filtered samples
//            // in both past and future and
//            // hence having smooth transitions
//            // between the filtered output of
//            // this function.
//            def go(s: Stream[F,Seq[Seq[Double]]]): Pull[F,Seq[Seq[Double]],Unit] = {
//                println("go start")
//                s.pull.uncons1.map {
//                    case Some((segment, tl)) => {
//                        val filteredSeqSeq = segment.transpose.zipWithIndex.map{ case (binSequence: Seq[Double], index: Int) =>
//                            val currentTreshold = noiseTresholds87Sliced.apply(index)
//                            val attenuationGains = binSequence.map{ v: Double =>
//                                if (v > currentTreshold) {
//                                    // mark for attenuation
//                                    1D
//                                }
//                                else {
//                                    // mark for no attenuation
//                                    0D
//                                }
//                            }
//                            val lowPassFilteredAttenuationGains = sosfilt(butterFilterCoeffs, attenuationGains)
//                            ///*
//                            binSequence.zip(lowPassFilteredAttenuationGains).map{case (p: Double, aGain: Double) =>
//                                p*pow(10, (1D-aGain)*noiseAttenuationdb/10) // Sxx_out = Sxx_in * 10 ^ ( - (1-attenuationGain [0 t0 1])*(attenuation [db]/10) )
//                            }.slice(5-1,10-1)
//                            //*/
//                            //lowPassFilteredAttenuationGains.slice(5-1,10-1)
//                            }.transpose
//                        println("go end")
//                        Pull.output(Segment.seq(filteredSeqSeq)) >> go(tl) // bitshift. Call itself on tail
//                    }
//                    case None => {
//                        Pull.done
//                    }
//                }
//            }
//            in => go(in).stream
//        }

        def flattenSeqSeqSlide[F[_],I]: Pipe[F,Seq[Seq[Double]],Seq[Double]] = {
            // Acts as a flatMap
            // Stream of Seq[Seq[Double]] --> Stream of Seq[Double] .
            def go(s: Stream[F,Seq[Seq[Double]]]): Pull[F,Seq[Double],Unit] = {
                s.pull.uncons1.flatMap {
                    case Some((segment, tl)) => {
                        // resultat av constructor Option
                        Pull.output(Segment.seq(segment)) >> go(tl) // bitshift. Call itself on tail
                    }
                    case None => {
                        Pull.done
                    }
                }
            }
            in => go(in).stream
        }

        def noiseReduce[F[_],I]: Pipe[F,Seq[Double],Seq[Double]] = {
            // The main real-time noise reduction
            // in the master thesis of
            // MSc student Ivar Thokle Hovden .
            // Goal: Compare results from
            // real-time to another offline
            // noise reduction in Python.

            // Important assumption:
            // Input: 40 Hz PSD stream of Seq[Double]
            _
              // Buffer up a sliding window of 15 PSDs, with 10 overlapping PSDs
              .through(stridedSeqSeqSlide(15, 10)) // 15 Seq[Double] window length, ovelapping with 10 Seq[Double]
              // Do the noise reduction on each bin of the sliding window of PSDs .
              .through(_.map{ stridedSeqSeq: Seq[Seq[Double]] =>
                  noiseReduceBinsWithTresholds(stridedSeqSeq, noiseTresholds87Sliced, noiseAttenuationdb)}
              )
              // Flatten back results, so that we are back to a stream of Seq[Double]
              .through(flattenSeqSeqSlide)
        }

        def detectActionPotentials[F[_],I]: Pipe[F,Seq[Double],Seq[Double]] = {
            // Assumption:
            // Input: 40 Hz PSD stream of Seq[Double]
            _
              .through(stridedSeqSeqSlide(15, 10))
              .through(_.map{ stridedSeqSeq: Seq[Seq[Double]] =>
                  detectActionPotentialsInBinsWithTresholds(stridedSeqSeq, noiseTresholds87Sliced, noiseAttenuationdb)}
              )
              // Flatten back results, so that we are back to a stream of Seq[Double]
              .through(flattenSeqSeqSlide)
        }

        def detectActionPotentialsAndLowPassFilter[F[_],I]: Pipe[F,Seq[Double],Seq[Double]] = {
            // Assumption:
            // Input: 40 Hz PSD stream of Seq[Double]
            _
              .through(stridedSeqSeqSlide(15, 10))
              .through(_.map{ stridedSeqSeq: Seq[Seq[Double]] =>
                  detectActionPotentialsInBinsWithTresholdsAndLowPassFilter(stridedSeqSeq, noiseTresholds87Sliced, noiseAttenuationdb)}
              )
              // Flatten back results, so that we are back to a stream of Seq[Double]
              .through(flattenSeqSeqSlide)
        }

        def detectAndSumActionPotentialsInSlidingWindow[F[_],I]: Pipe[F,Seq[Double],Seq[Double]] = {
            // Assumption:
            // Input: 40 Hz PSD stream of Seq[Double]
            _
              // Buffer up a sliding window of 15 PSDs, with 10 overlapping PSDs
              .through(stridedSeqSeqSlide(15, 10))
              // 1D of detected action potential (based fixed tresholds from
              // noiseTresholds87Sliced), 0D if detected.
              .through(_.map{ stridedSeqSeq: Seq[Seq[Double]] =>
                  detectActionPotentialsInBinsWithTresholds(stridedSeqSeq, noiseTresholds87Sliced, noiseAttenuationdb)}
              )
              // Flatten back results, so that we are back to a stream of Seq[Double]
              .through(flattenSeqSeqSlide)
              // Buffer up a sliding window of 6*40 = 240 Seqs
              // with 0.8*40*6 = 192 Seqs ovelap.
              .through(stridedSeqSeqSlide(240, 192))
              // Count the number of detected action potentials
              // by summin each bin in this overlapping sliding
              // window.
              .through(_.map{ stridedSeqSeq: Seq[Seq[Double]] =>
                  countActionPotentialsInBins(stridedSeqSeq)}
              )
              // Flatten back results, so that we are back to a stream of Seq[Double]
              .through(flattenSeqSeqSlide)
        }

        def tickSource[F[_]](period: FiniteDuration)(implicit s: Effect[F], t: Scheduler, ec: ExecutionContext): Stream[F,Unit] = {
            // Used in throttlerPipe to slow down the reading of the file
            // to realistic speed in a real-time scenario.
            t.fixedRate(period)
        }
        def chunkify[F[_],I]: Pipe[F,Seq[I],I] = {
            // Transforms a stream of Seq[Int]
            // to a stream of Int , contents of the Seq[Int]
            def go(s: Stream[F,Seq[I]]): Pull[F,I,Unit] = {
                // take 1 array, transform it to stream
                s.pull.uncons1.flatMap {
                    case Some((segment, tl)) => {
                        // resultat av constructor Option
                        Pull.output(Segment.seq(segment)) >> go(tl) // bitshift. Call itself on tail
                    }
                    case None => {
                        Pull.done
                    }
                }
            }
            in => go(in).stream
        }
        def vectorize[F[_],I](length: Int): Pipe[F,I,Vector[I]] = {
            // Partitions a stream of Int to a stream of
            // Vector[Int] of length n , non-overlapping.
            def go(s: Stream[F,I]): Pull[F,Vector[I],Unit] = {
                s.pull.unconsN(length.toLong, false).flatMap {
                    case Some((segment, tl)) => {
                        Pull.output1(segment.force.toVector) >> go(tl)
                    }
                    case None => {
                        Pull.done
                    }
                }
            }
            in => go(in).stream
        }
        def throttlerPipe[F[_]: Effect,I](Fs: Int, resolution: FiniteDuration)(implicit ec: ExecutionContext): Pipe[F,I,I] = {
            // Throttles the stream of Int to the given time resolution.
            // Real-time resolution (resolution) is 0.125 seconds
            // when sampling rate (Fs) is 10000 Hz
            val ticksPerSecond = (1.second/resolution) // With a resolution of 0.125 seconds, ticksPerSecond will be 8 .
            val elementsPerTick = (Fs/ticksPerSecond).toInt // With a tickPerSecond of 8, this will be 1250 elementsPerTick .
            _.through(vectorize(elementsPerTick)) // Make Vector[Int] from segment of 1250 objects .
                .zip(tickSource(resolution)) // Here is the throttling. It is set to the given time resolution of 0.125 seconds.
                                             // This results in each segment (1250 object Vector[Int]) being passed through
                                             // the pipe each 0.125 second.
                                             // This is the optimal near live / real-time pass-through speed of the vectors
                                             // given that the they (the segments segments) overlap 80 % in time. 
                                             // The 80 % overlap in time is later achieved using stridedSlide with 
                                             // windowWidth: 1250 and
                                             // overlap: 1000 .
                .through(_.map(_._1)) // Remove the index from zip by only letting the actual data through .
                .through(chunkify) // Lastly, the throttled segments/vectors are flatMapped to a stream of integers, using Chunkify .
        }

        def stridedSlide[F[_],I](windowWidth: Int, overlap: Int): Pipe[F,I,Vector[I]] = {
            /**
              * Groups inputs in fixed size chunks by passing a "sliding window"
              * of size `windowWidth` and with an overlap `overlap` over them.
              *
              * @example {{{
              * scala> Stream(1, 2, 3, 4, 5, 6, 7).stridedSliding(3, 1).toList
              * res0: List[Vector[Int]] = List(Vector(1, 2, 3), Vector(3, 4, 5), Vector(5, 6, 7))
              * }}}
              * @throws scala.IllegalArgumentException if `n` <= 0
              */
            require(windowWidth > 0,       "windowWidth must be > 0")
            require(windowWidth > overlap, "windowWidth must be wider than overlap")
            val stepsize = windowWidth - overlap
            def go(s: Stream[F,I], earlier: Vector[I]): Pull[F,Vector[I],Unit] = {
                s.pull.unconsN(stepsize.toLong, false).flatMap {
                    if (earlier.length == overlap){
                        case Some((seg, tl)) => {
                            val forced = earlier ++ seg.force.toVector
                            Pull.output1(forced) >> go(tl, forced.drop(stepsize))
                        }
                        case None => {
                            Pull.done
                        }
                    }
                    else {
                        case Some((seg, tl)) => {
                            val forced = earlier ++ seg.force.toVector
                            go(tl, forced)
                        }
                        case None => {
                            Pull.done
                        }
                    }
                }
            }
            in => go(in, Vector()).stream
        }

        def readCSVToSlidingWindows[F[_]: Effect](fileToStream: Path, selected_electrode_id: Int, Fs: Int): Stream[F,Vector[Int]] = {
            // Read the text lines of the filToStream csv file,
            // take the selected electrode voltage of the line
            // then throttle stream to live speed
            // and stream the lines to the pipe as a Vector[Int]
            // in 80 % overlapping windows of 1250 voltages
            // using stridedSlide.
            println(s"set to stream electrode ID=$selected_electrode_id")
            val reader = io.file.readAll[F](fileToStream, 4096)
                .through(text.utf8Decode)
                .through(text.lines).drop(7) // Drop 7 first lines because fileToStream comes directly from MCS DataManager ASCII export.
                //.through(_.map{ csvLine => csvLine.split(",").map(_.toInt).toList.tail})
                .through(_.map{ csvLine => csvLine.split(",").tail.apply(selected_electrode_id).toInt}) // split each text (String) line 
                                                                                     // to an array of 61 Chars representing the
                                                                                     // TimeStamp and the electrode voltages, then      
                                                                                     // remove the TimeStamp using tail.
                                                                                     // Then select only electrode with ID=10 .
                                                                                     // The Char representing the integer value 
                                                                                     // of electrode with the selected ID
                                                                                     // is then converted to Int
                
                //.through(throttlerPipe(Fs, 0.125.second)) // make the stream near live speed by slowing it down
                                                          // to segments of 1250 integers being delivered each 0.125 second.
                                                          // This is near live streaming throughput with a sampling rate of 
                                                          // Fs: 10000 Hz
            
                .through(stridedSlide(1250, 1000)) // transform the stream to sliding windows of segments of 1250 objects
                                                   // with 80 % overlap. The parameters fit optimally together with 
                                                   // throttlerPipe(10000 Hz, 0.125 seconds) as used above
            
                .handleErrorWith{
                    case e: java.lang.NumberFormatException => { println("Record done"); Stream.empty}
                    case e: Exception => { println(s"Very bad error $e"); Stream.empty }
                    case _ => { println("I don't know what happened..."); Stream.empty }
                }
            reader
        }
        //(implicit ec: ExecutionContext, s: Scheduler)
        def computePowerSpectralDensity[F[_],I]: Pipe[F,Vector[Int],Seq[Double]] = {
            _.through(_.map { window =>
                val windowArray = window.map(_.toDouble).to[Seq]
                val N = windowArray.length
                // output of psd is a Seq[Tuple2(Double, Double)]
                psd(windowArray, Fs.toDouble)
                  .map{case (f, p) => (f, p*(N/Fs.toDouble))}.slice(startFreq, endFreq+1)
                  .map(_._2) // Only take Pxx, _._1 is the frequency range 296 - 3000 Hz .
            })
        }
        val dataStream = readCSVToSlidingWindows[IO](fileToStream, selected_electrode_id, Fs)
          .through(computePowerSpectralDensity) // Should always be enabled

          // ----> NB! Only one (1) of the following four (4)
          // .through operations should be selected, if any. All can be disabled too <----
          // ---------------

          // 1. Seq[Double] --> Seq[Double],
          // where output Double values are 1D or 0D for detected
          // or not detected spike based on the predefined
          // tresholds in noiseTresholds87Path

          //.through(detectActionPotentials)

          // 2. The same as 1., and additionally summing up
          // the detections on a 240 (6s) 192 (80%) overlapping
          // sliding window. Each new Seq[Double] thus
          // should arrive each 240*(1-0.2)/40 = 48/40 = 1.2 second
          // or 1/1.2 = ~0.83 Hz,
          // in a live scenario. This should be ideal for further
          // plotting and/or PCA projection and plotting.
          // Remembering the last 6 second in the form
          // of counting the number of peaks from APs,
          // has proven to give god results for PCA on the
          // preprocessed data in a comparable offline
          // analysis in Python. The main difference
          // here (real-time) from offline 6s sliding
          // window, is the following: In offline
          // analysis, number of peaks in APs are counted.
          // In this (real-time) analysis, the number of times
          // an AP goes above a fixed treshold for that bin,
          // is counted. Hence, what is a single 1 peak count
          // in the offline analysis, would likely be multiple
          // above treshold counts in the real-time analysis.

          .through(detectAndSumActionPotentialsInSlidingWindow)

          // 3. Same as 1, with an additional low pass filter
          // on top of the spike detections.
          // It is also the intermediate step inside .through(noiseReduce)

          //.through(detectActionPotentialsAndLowPassFilter)

          // 4. The complete noise reduction that does in
          // the same as .through(detectActionPotentialsAndLowPassFilter)
          // and then uses the filtered detected action potentials to
          // noise reduce the actual(raw) PSDs that come from
          // .through(computePowerSpectralDensity) .
          // Compare with the raw PSD output from not uncommenting any
          // any of the four points, to se the difference in background
          // noise.

          //.through(noiseReduce)

          // ---------------

        def sendStreamInTCP[F[_]](socket: Socket[F], dataStream: Stream[F, Vector[Int]]): Stream[F, Unit] = {
            dataStream
                //.through(_.map(_.toString)) // convert the 80 % overlapping Vectors to String
                .through(_.map(_.mkString(","))) // convert the 80 % overlapping Vectors to String
                .intersperse("\n") // add newline between the windows now stored as Strings
                .through(text.utf8Encode)
                .through(socket.writes(None))
                .handleErrorWith{
                    case e: java.io.IOException => { 
                        println(s"Connection ended because of $e")
                        println("now exiting system")
                        System.exit(0)                       
                        Stream.empty 
                    }
                    case _ => { println("I don't know what happened...")
                        //System.exit(0)
                        Stream.empty                        
                    }
                }
        }
        def writeStreamToFile[F[_]](filePathAndName: String, dataStream: Stream[F, Seq[Double]])(implicit F: Sync[F]): F[Unit] = {
            println("writing preprocessed data to")
            println(outputFilePathAndName)
            dataStream
              .through(_.map(_.mkString(";").replaceAllLiterally(".",","))) // convert the 80 % overlapping Vectors to String
              .intersperse("\n") // add newline between the windows now stored as Strings
              .through(text.utf8Encode)
              .through(io.file.writeAll(Paths.get(filePathAndName)))
              .compile.drain
        }
        //println(s"now starting the server on port $TCPPort")
        /*
        server[IO](new InetSocketAddress("localhost", TCPPort))
            .flatMap(stream => stream.flatMap(socket => sendStreamInTCP[IO](socket, dataStream)))
            .compile
            .drain
            .unsafeRunSync()
        */
        // version that terminates the program after having sent the file to one client.
        /*
        Stream.eval(fs2.async.signalOf[IO, Boolean](false)).flatMap { interruptSignal =>
            server[IO](new InetSocketAddress("localhost", TCPPort))
                .interruptWhen(interruptSignal)
                .flatMap(stream => stream.flatMap(socket => sendStreamInTCP[IO](socket, dataStream)) ++ Stream.eval_(interruptSignal.set(true)))
        }.compile.drain.unsafeRunSync()
        */
        val u: Unit = writeStreamToFile[IO](outputFilePathAndName, dataStream).unsafeRunSync()
    }
}

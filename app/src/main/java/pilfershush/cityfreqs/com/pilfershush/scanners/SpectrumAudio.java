package pilfershush.cityfreqs.com.pilfershush.scanners;
/*

test for quicker/efficient AudioRecord methods,
    form of hpf,
    and magnitude scanner

taken from:
https://github.com/billthefarmer/scope/blob/master/src/main/java/org/billthefarmer/scope/SpectrumActivity.java
Bill Farmer	 william j farmer [at] yahoo [dot] co [dot] uk.

 */

import pilfershush.cityfreqs.com.pilfershush.MainActivity;
import pilfershush.cityfreqs.com.pilfershush.assist.AudioSettings;

public class SpectrumAudio {
    // Data
    protected double frequency;
    protected double fps;

    private static final int OVERSAMPLE = 4;
    private static final int SAMPLES = 4096; // this as a final?
    private static final int RANGE = SAMPLES / 2;
    private static final int STEP = SAMPLES / OVERSAMPLE;
    private static final int N = 4;
    private static final int M = 16;

    private static final double MIN = 0.5;
    private static final double EXPECT = 2.0 * Math.PI * STEP / SAMPLES;

    private int bufferSize;
    private int counter;

    private double buffer[];
    private double xr[];
    private double xi[];
    protected double xa[];
    private double xp[];
    private double xf[];

    protected SpectrumAudio() {
        buffer = new double[SAMPLES];

        xr = new double[SAMPLES];
        xi = new double[SAMPLES];

        xa = new double[RANGE];
        xp = new double[RANGE];
        xf = new double[RANGE];
    }

    protected void initSpectrumAudio(int bufferSize, int sampleRate) {
        // don't use this?
        this.bufferSize = bufferSize;
        if (bufferSize != SAMPLES) {
            MainActivity.logger("initSpectrumAudio bufferSize diff error.");
        }

        // Calculate fps
        fps = (double) sampleRate / SAMPLES;
        counter = 0;
    }

    protected void checkSpectrumAudio(short[] data) {
        //
        // placeholder function for testing SpectrumAudio

        if (data != null) {
            processSpectrumAudio(data);
        }

    }

    protected void finishSpectrumAudio() {
        MainActivity.logger("spectrumAudio freq count: " + counter);
        //MainActivity.logger(String.format("spectrumAudio freq: %1.1f Hz", frequency));
    }

    private void processSpectrumAudio(short[] data) {

        // pass the data short buffer in to this, check for freqs above
        // AudioSettings.DEFAULT_FREQUENCY_MIN;

        double dmax = 0.0;
        double norm;
        double window;
        double real;
        double imag;
        double p;
        double dp;
        double df;
        double max;
        double level;
        double dB;

        if (data != null) {
            System.arraycopy(buffer, STEP, buffer, 0, SAMPLES - STEP);

            for (int i = 0; i < STEP; i++) {
                buffer[(SAMPLES - STEP) + i] = data[i];
            }

            if (dmax < 4096.0)
                dmax = 4096.0;

            norm = dmax;
            dmax = 0.0;

            for (int i = 0; i < SAMPLES; i++) {
                // Find the magnitude
                if (dmax < Math.abs(buffer[i])) {
                    dmax = Math.abs(buffer[i]);
                }

                // uses a Hann(ing) window
                window = 0.5 - 0.5 * Math.cos(AudioSettings.PI2 * i / SAMPLES);

                // Normalise and window the input data
                xr[i] = buffer[i] / norm * window;
            }

            fftr(xr, xi);
            // Process FFT output
            for (int i = 1; i < RANGE; i++) {
                real = xr[i];
                imag = xi[i];

                xa[i] = Math.hypot(real, imag);

                // Do frequency calculation
                p = Math.atan2(imag, real);
                dp = xp[i] - p;

                xp[i] = p;

                // Calculate phase difference
                dp -= i * EXPECT;

                int qpd = (int)(dp / Math.PI);

                if (qpd >= 0) {
                    qpd += qpd & 1;
                }
                else {
                    qpd -= qpd & 1;
                }
                dp -=  Math.PI * qpd;

                // Calculate frequency difference
                df = OVERSAMPLE * dp / (2.0 * Math.PI);

                // Calculate actual frequency from slot frequency plus
                // frequency difference and correction value
                xf[i] = i * fps + df * fps;
            }

            // Maximum FFT output
            max = 0.0;
            // Find maximum value
            for (int i = 1; i < RANGE; i++) {
                if (xa[i] > max) {
                    max = xa[i];
                    frequency = xf[i];
                }
            }

            level = 0.0;

            for (int i = 0; i < STEP; i++) {
                level += ((double) data[i] / 32768.0) * ((double) data[i] / 32768.0);
            }
            level = Math.sqrt(level / STEP) * 2.0;


            dB = Math.log10(level) * 20.0;

            if (dB < -80.0) {
                dB = -80.0;
            }


            // check frequency and dB
            if (max > MIN) {
                // check frequency (%1.1fHz)
                // check its level over threshold
                // produces variations, so may need to group within range then count
                if (frequency >= AudioSettings.DEFAULT_FREQUENCY_MIN) {
                    //MainActivity.logger(String.format("spectrumAudio freq: %1.1f Hz", frequency));
                    counter++;
                }
            }
            else {
                frequency = 0.0;
            }
        }
        // end of process
    }

    // Real to complex FFT, ignores imaginary values in input array
    private void fftr(double ar[], double ai[]) {
        final int n = ar.length;
        final double norm = Math.sqrt(1.0 / n);

        for (int i = 0, j = 0; i < n; i++) {

            if (j >= i) {
                double tr = ar[j] * norm;
                ar[j] = ar[i] * norm;
                ai[j] = 0.0;
                ar[i] = tr;
                ai[i] = 0.0;
            }

            int m = n / 2;
            while (m >= 1 && j >= m) {
                j -= m;
                m /= 2;
            }
            j += m;
        }

        for (int mmax = 1, istep = 2 * mmax; mmax < n; mmax = istep, istep = 2 * mmax) {
            double delta = Math.PI / mmax;

            for (int m = 0; m < mmax; m++) {
                double w = m * delta;
                double wr = Math.cos(w);
                double wi = Math.sin(w);

                for (int i = m; i < n; i += istep) {
                    int j = i + mmax;
                    double tr = wr * ar[j] - wi * ai[j];
                    double ti = wr * ai[j] + wi * ar[j];
                    ar[j] = ar[i] - tr;
                    ai[j] = ai[i] - ti;
                    ar[i] += tr;
                    ai[i] += ti;
                }
            }
        }
    }
}
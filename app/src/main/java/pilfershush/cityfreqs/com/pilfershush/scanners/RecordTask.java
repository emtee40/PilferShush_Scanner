package pilfershush.cityfreqs.com.pilfershush.scanners;

import android.annotation.SuppressLint;
import android.media.AudioRecord;
import android.os.AsyncTask;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import pilfershush.cityfreqs.com.pilfershush.MainActivity;
import pilfershush.cityfreqs.com.pilfershush.PilferShushScanner;
import pilfershush.cityfreqs.com.pilfershush.assist.AudioSettings;
import pilfershush.cityfreqs.com.pilfershush.assist.WriteProcessor;
import pilfershush.cityfreqs.com.pilfershush.scanners.FreqDetector.RecordTaskListener;

public class RecordTask extends AsyncTask<Void, Integer, String> {
    private static final String TAG = "RecordTask";

    private short[] bufferArray;
    private double[] recordScan;
    private double[] scanArray;
    private RecordTaskListener recordTaskListener;
    private AudioRecord audioRecord;
    private AudioSettings audioSettings;
    private int bufferRead;
    private double minMagnitude;
    private int freqStepper;
    private int candidateFreq;
    private Integer[] tempBuffer;
    private ArrayList<Integer[]> bufferStorage;
    private HashMap<Integer, Integer> freqMap;
    private byte[] byteBuffer;

    public RecordTask(AudioSettings audioSettings, int freqStepper, double magnitude) {
        this.audioSettings = audioSettings;
        this.freqStepper = freqStepper;
        minMagnitude = magnitude;
        bufferArray = new short[audioSettings.getBufferSize()];
        bufferStorage = new ArrayList<Integer[]>();

        if (audioRecord == null) {
            try {
                audioRecord = new AudioRecord(audioSettings.getAudioSource(),
                        audioSettings.getSampleRate(),
                        audioSettings.getChannel(),
                        audioSettings.getEncoding(),
                        audioSettings.getBufferSize());

                logger("RecordTask ready.");
            }
            catch (Exception ex) {
                ex.printStackTrace();
                logger("RecordTask failed.");
            }
        }
    }

    public void setOnResultsListener(RecordTaskListener recordTaskListener) {
        this.recordTaskListener = recordTaskListener;
    }

    public boolean runCurrentBufferScan(ArrayList<Integer> freqList) {
        // get rid of audioRecord
        if (audioRecord != null) {
            audioRecord = null;
        }
        if (bufferStorage != null) {
            activityLogger("run Buffer Scan...");
            return magnitudeBufferScan(AudioSettings.DEFAULT_WINDOW_TYPE, freqList);
        }
        else {
            activityLogger("Buffer Scan storage null.");
            return false;
        }
    }

    /********************************************************************/

    protected boolean hasBufferStorage() {
        if (bufferStorage != null) {
            return !bufferStorage.isEmpty();
        }
        return false;
    }

    protected ArrayList<Integer[]> getBufferStorage() {
        return bufferStorage;
    }

    protected boolean hasFrequencyCountMap() {
        if (freqMap != null) {
            return freqMap.size() > 0;
        }
        return false;
    }

    protected int getFrequencyCountMapSize() {
        if (freqMap != null) {
            return freqMap.size();
        }
        return 0;
    }

    protected HashMap<Integer, Integer> getFrequencyCountMap() {
        return freqMap;
    }

    public byte[] getRecordBuffer() {
        return byteBuffer;
    }

    /********************************************************************/

    @Override
    protected void onPreExecute() {
        super.onPreExecute();
    }

    @Override
    protected void onProgressUpdate(Integer... paramArgs) {
        if (recordTaskListener == null) {
            logger("onProgress listener null.");
            return;
        }

        if (paramArgs[0] != null) {
            recordTaskListener.onSuccess(paramArgs[0].intValue());
        }
        else {
            recordTaskListener.onFailure("RecordTaskListener failed, no params.");
            logger("listener onFailure.");
        }
    }

    @Override
    protected String doInBackground(Void... paramArgs) {
        if (isCancelled()) {
            // check
            logger("isCancelled check");
            return "isCancelled()";
        }
        // check audioRecord object first
        if ((audioRecord != null) || (audioRecord.getState() == AudioRecord.STATE_INITIALIZED)) {
            try {
                audioRecord.startRecording();
                logger("audioRecord started...");
                audioRecord.setPositionNotificationPeriod(audioSettings.getBufferSize() / 2);
                audioRecord.setRecordPositionUpdateListener(new AudioRecord.OnRecordPositionUpdateListener() {
                    public void onMarkerReached(AudioRecord audioRecord) {
                        logger("marker reached");
                    }

                    public void onPeriodicNotification(AudioRecord audioRecord) {
                        magnitudeRecordScan(AudioSettings.DEFAULT_WINDOW_TYPE);
                        MainActivity.visualiserView.updateVisualiser(byteBuffer);
                    }
                });

                do {
                    bufferRead = audioRecord.read(bufferArray, 0, audioSettings.getBufferSize());
                } while (!isCancelled());
            }
            catch (IllegalStateException exState) {
                exState.printStackTrace();
                logger("AudioRecord start recording failed.");
            }
        }
        return "RecordTask finished";
    }

    @Override
    protected void onPostExecute(String paramString) {
        logger("Post execute: " + paramString);
    }

    @Override
    protected void onCancelled() {
        logger("onCancelled called.");
        bufferRead = 0;
        try {
            if (WriteProcessor.AUDIO_OUTPUT_STREAM != null) {
                WriteProcessor.AUDIO_OUTPUT_STREAM.close();
            }
        }
        catch (IOException e) {
            e.printStackTrace();
            logger("onCancelled write stream close error.");
        }

        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            logger("audioRecord stop and release.");
        }
        else {
            logger("audioRecord is null.");
        }
    }

    /********************************************************************/

    private void magnitudeRecordScan(int windowType) {
        if (bufferRead > 0) {
            recordScan = new double[audioSettings.getBufferSize()];
            tempBuffer = new Integer[audioSettings.getBufferSize()];
            byteBuffer = new byte[audioSettings.getBufferSize()];

            for (int i = 0; i < recordScan.length; i++) {
                recordScan[i] = (double)bufferArray[i];
                tempBuffer[i] = (int)bufferArray[i];
                byteBuffer[i] = (byte)bufferArray[i];
            }

            // save audio buffer to non-header pcm file, boolean switch here
            if (PilferShushScanner.WRITE_FILE) {
                try {
                    WriteProcessor.AUDIO_OUTPUT_STREAM.write(byteBuffer, 0, audioSettings.getBufferSize());
                } catch (IOException e) {
                    e.printStackTrace();
                    logger("AudioRecord write stream error.");
                }
            }

            // default value set to 2
            recordScan = windowArray(windowType, recordScan);
            candidateFreq = AudioSettings.DEFAULT_FREQUENCY_MIN;
            Goertzel goertzel;
            double candidateMag;

            while (candidateFreq <= AudioSettings.DEFAULT_FREQUENCY_MAX) {
                // look for any of our freqs here, increment by freqStepper
                // this will result in a found candidate for anything in our ranges...
                goertzel = new Goertzel((float)audioSettings.getSampleRate(), (float)candidateFreq, recordScan);
                goertzel.initGoertzel();
                // get its magnitude
                candidateMag = goertzel.getOptimisedMagnitude();
                // check if above threshold
                if (candidateMag >= minMagnitude) {
                    // saved here for later analysis
                    bufferStorage.add(tempBuffer);
                    publishProgress(new Integer[]{Integer.valueOf(candidateFreq)});
                }
                // next freq for loop
                candidateFreq += freqStepper;
            }
        }
        else {
            logger("bufferRead empty");
        }
    }

    @SuppressLint("UseSparseArrays")
    private boolean magnitudeBufferScan(int windowType, ArrayList<Integer> freqList) {
        // use existing bufferStorage array for scanning.
        // will result in same findings as magnitudeRecordScan()...
        if ((freqList == null) || (freqList.isEmpty())) {
            activityLogger("Buffer scan list empty.");
            return false;
        }

        if (bufferStorage != null) {
            activityLogger("Start buffer scanning in " + bufferStorage.size() + " buffers.");

            // bufferStorage is ArrayList of Integer arrays,
            // each Integer array *may* contain a binMod signal
            freqMap = new HashMap<Integer, Integer>();
            int freq;
            double candidateMag;
            ArrayList<Integer> freqCounter;
            Goertzel goertzel;

            //TODO
            // may want a maximum on this cos it could get big and ugly...
            for (Integer[] tempArray : bufferStorage) {
                // in each array, scan for magnitude
                scanArray = new double[tempArray.length];
                for (int i = 0; i < scanArray.length; i++) {
                    scanArray[i] = (double)tempArray[i];
                }

                //
                // default value set to 2
                scanArray = windowArray(windowType, scanArray);
                // end windowing
                for (int checkFreq : freqList) {
                    freq = 0;
                    freqCounter = new ArrayList<Integer>();

                    // range here may be too small..
                    for (freq = checkFreq - AudioSettings.MAX_FREQ_STEP;
                         freq <= checkFreq + AudioSettings.MAX_FREQ_STEP;
                         freq += freqStepper) {

                        // set Goertzel up to count candidates in dArr
                        goertzel = new Goertzel((float)audioSettings.getSampleRate(), (float)freq, scanArray);
                        goertzel.initGoertzel();

                        // get its magnitude
                        candidateMag = goertzel.getOptimisedMagnitude();
                        // set magnitude floor, raises it
                        // check if above threshold
                        if (candidateMag >= minMagnitude) {
                            // the freq has a magnitude,
                            // note it and then allow loop to continue
                            freqCounter.add(freq);
                        }
                    }
                    // store any finds for later analysis
                    if (!freqCounter.isEmpty()) {
                        mapFrequencyCounts(freqCounter);
                    }
                }
            }
            // end bufferStorage loop thru
        }
        activityLogger("finished  Buffer Scan loop.");
        return true;
    }

    /********************************************************************/

    private double[] windowArray(int windowType, double[] dArr) {
        int i;
        // default value set to 2
        switch (windowType) {
            case 1:
                // Hann(ing) Window
                for (i = 0; i < dArr.length; i++) {
                    dArr[i] = dArr[i] * (0.5d - (0.5d * Math.cos((AudioSettings.PI2 * ((double) i)) / dArr.length)));
                }
                break;
            case 2:
                // Blackman Window
                for (i = 0; i < dArr.length; i++) {
                    dArr[i] = dArr[i] * ((0.42659d - (0.49659d * Math.cos((AudioSettings.PI2 *
                            ((double) i)) / dArr.length))) + (0.076849d * Math.cos((AudioSettings.PI4 * ((double) i)) / dArr.length)));
                }
                break;
            case 3:
                // Hamming Window
                for (i = 0; i < dArr.length; i++) {
                    dArr[i] = dArr[i] * (0.54d - (0.46d * Math.cos((AudioSettings.PI2 * ((double) i)) / dArr.length)));
                }
                break;
            case 4:
                // Nuttall Window
                for (i = 0; i < dArr.length; i++) {
                    dArr[i] = dArr[i] * (((0.355768d - (0.487396d * Math.cos((AudioSettings.PI2 * ((double) i)) / dArr.length))) +
                            (0.144232d * Math.cos((AudioSettings.PI4 * ((double) i)) / dArr.length))) -
                            (0.012604d * Math.cos((AudioSettings.PI6 * ((double) i)) / dArr.length)));
                }
                break;
        }
        return dArr;
    }


    private void mapFrequencyCounts(ArrayList<Integer> freqList) {
        // SparseIntArray is suggested...
        // this only counts, order of occurrence is not preserved.

        for (int freq : freqList) {
            if (freqMap.containsKey(freq)) {
                freqMap.put(freq, freqMap.get(freq) + 1);
            }
            else {
                freqMap.put(freq, 1);
            }
        }
    }

    /********************************************************************/

    private void activityLogger(String message) {
        MainActivity.logger(message);
    }

    private void logger(String message) {
        Log.d(TAG, message);
    }
}


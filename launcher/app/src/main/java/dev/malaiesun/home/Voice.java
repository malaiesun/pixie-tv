package dev.malaiesun.home;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Mic button features. Gemini: hand off to the system assistant. HA Assist: Android's speech
 * recognizer (Google's, uses the remote's mic) turns speech into text, Home Assistant's
 * conversation agent acts on it, and the reply is shown and spoken.
 */
final class Voice implements RecognitionListener {
    private final PixieService s;
    private SpeechRecognizer sr;
    private TextToSpeech tts;
    private boolean ttsReady;
    private String pendingSpeech;

    Voice(PixieService s) { this.s = s; }

    void gemini() {
        try {
            s.startActivity(new Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            s.overlay.toast("Sorry, the Assistant isn't available.");
        }
    }

    void haAssist() {
        if (s.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            s.overlay.card(Panels.HA, "Microphone permission is missing (adb: pm grant dev.malaiesun.home android.permission.RECORD_AUDIO)", false, 6000);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(s)) {
            s.overlay.card(Panels.HA, "No speech recognizer on this TV.", false, 5000);
            return;
        }
        if (sr == null) {
            sr = SpeechRecognizer.createSpeechRecognizer(s);
            sr.setRecognitionListener(this);
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, s.getPackageName());
        s.overlay.card(Panels.HA, "I'm listening…", true, 20000);
        try { sr.startListening(i); } catch (Exception e) { s.overlay.card(Panels.HA, "Couldn't start listening: " + e.getMessage(), false, 5000); }
    }

    /** Text straight to HA Assist (from the API, rules or a PC). */
    void ask(String text) {
        if (text == null || text.trim().isEmpty()) return;
        s.overlay.card(Panels.HA, "“" + text + "”", true, 20000);
        s.ha.conversation(s.cfg, text, s.ui, (reply, err) -> {
            String out = err != null ? "Home Assistant: " + err : reply;
            s.overlay.card(Panels.HA, "“" + text + "”\n\n" + out, false, 9000);
            if (err == null) speak(reply);
            s.fireEvent("assist", null);
        });
    }

    private void speak(String text) {
        if (text == null || text.isEmpty()) return;
        if (tts == null) {
            pendingSpeech = text;
            tts = new TextToSpeech(s, st -> {
                ttsReady = st == TextToSpeech.SUCCESS;
                if (ttsReady && pendingSpeech != null) { tts.speak(pendingSpeech, TextToSpeech.QUEUE_FLUSH, null, "pixie"); pendingSpeech = null; }
            });
        } else if (ttsReady) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pixie");
        }
    }

    // ---- RecognitionListener ----

    @Override public void onReadyForSpeech(Bundle params) { s.overlay.card(Panels.HA, "I'm listening, go ahead!", true, 20000); }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rmsdB) { s.overlay.cardLevel(rmsdB); }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { s.overlay.card(Panels.HA, "Hmm, let me check…", true, 20000); }

    @Override public void onError(int error) {
        String why;
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH: case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: why = "Didn't catch that."; break;
            case SpeechRecognizer.ERROR_AUDIO: why = "Microphone problem."; break;
            case SpeechRecognizer.ERROR_NETWORK: case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: why = "Speech service is offline."; break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: why = "Microphone permission missing."; break;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: why = "Recognizer busy, try again."; break;
            default: why = "Speech error " + error + ".";
        }
        s.overlay.card(Panels.HA, why, false, 4000);
    }

    @Override public void onResults(Bundle results) {
        ArrayList<String> r = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (r == null || r.isEmpty()) { onError(SpeechRecognizer.ERROR_NO_MATCH); return; }
        ask(r.get(0));
    }

    @Override public void onPartialResults(Bundle partial) {
        ArrayList<String> r = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (r != null && !r.isEmpty() && !r.get(0).isEmpty()) s.overlay.card(Panels.HA, r.get(0) + "…", true, 20000);
    }

    @Override public void onEvent(int eventType, Bundle params) { }

    void destroy() {
        if (sr != null) { sr.destroy(); sr = null; }
        if (tts != null) { tts.shutdown(); tts = null; }
    }
}

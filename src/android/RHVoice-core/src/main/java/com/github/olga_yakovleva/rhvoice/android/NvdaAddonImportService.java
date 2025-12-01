package com.github.olga_yakovleva.rhvoice.android;

import android.app.IntentService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import com.google.common.collect.ImmutableList;
import com.squareup.moshi.Moshi;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipFile;

public final class NvdaAddonImportService extends IntentService {
    public static final String ACTION_IMPORT_ADDON = "org.rhvoice.action.IMPORT_NVDA_ADDON";
    public static final String EXTRA_URI = "uri";
    private static final String TAG = "RHVoice.NvdaAddonImport";

    public NvdaAddonImportService() {
        super("RHVoice.NvdaAddonImportService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null || !ACTION_IMPORT_ADDON.equals(intent.getAction()))
            return;
        Uri uri = intent.getParcelableExtra(EXTRA_URI);
        if (uri == null)
            uri = intent.getData();
        if (uri == null)
            return;
        importAddon(uri);
    }

    private void importAddon(Uri uri) {
        final File workDir = getDir("imports", 0);
        if (!workDir.exists())
            workDir.mkdirs();
        final File zipFile = new File(workDir, "addon.nvda-addon");
        if (zipFile.exists())
            zipFile.delete();
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(zipFile)) {
            DataPack.copyBytes(in, out, null);
        } catch (IOException e) {
            notifyUser(R.string.import_addon_failed);
            if (BuildConfig.DEBUG)
                Log.e(TAG, "Failed to copy addon", e);
            return;
        }
        AddonMeta meta = parseMeta(zipFile);
        if (meta == null) {
            notifyUser(R.string.import_addon_invalid);
            return;
        }
        if (!install(meta, zipFile)) {
            notifyUser(R.string.import_addon_failed);
            return;
        }
        notifyUser(R.string.import_addon_success);
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(RHVoiceService.ACTION_CHECK_DATA));
        sendBroadcast(new Intent(TextToSpeech.Engine.ACTION_TTS_DATA_INSTALLED));
    }

    private AddonMeta parseMeta(File zipFile) {
        try (ZipFile zip = new ZipFile(zipFile)) {
            Properties langProps = readProps(zip, "langdata/language.info");
            Properties localeProps = readProps(zip, "langdata/locale.info");
            Properties voiceProps = readProps(zip, "data/voice.info");
            if (langProps == null || localeProps == null || voiceProps == null)
                return null;
            LanguageResource lang = new LanguageResource();
            lang.name = langProps.getProperty("name", "Unknown");
            lang.lang2code = localeProps.getProperty("language2", "");
            lang.lang3code = localeProps.getProperty("language3", "");
            lang.testMessage = getDefaultTestMessage(lang.lang2code);
            lang.pseudoEnglish = false;
            lang.version.major = parseInt(langProps.getProperty("format"), 1);
            lang.version.minor = parseInt(langProps.getProperty("revision"), 0);
            VoiceResource voice = new VoiceResource();
            voice.name = voiceProps.getProperty("name", "Voice");
            voice.ctry2code = localeProps.getProperty("country2", "");
            voice.ctry3code = localeProps.getProperty("country3", "");
            voice.accent = "";
            voice.version.major = parseInt(voiceProps.getProperty("format"), 1);
            voice.version.minor = parseInt(voiceProps.getProperty("revision"), 0);
            lang.voices = ImmutableList.of(voice);
            lang.index();
            return new AddonMeta(lang, voice);
        } catch (IOException e) {
            if (BuildConfig.DEBUG)
                Log.e(TAG, "Failed to parse addon", e);
            return null;
        }
    }

    private boolean install(AddonMeta meta, File zipFile) {
        final LanguagePack langPack = new LanguagePack(meta.lang);
        final VoicePack voicePack = new VoicePack(langPack, meta.voice);
        final int langVersion = langPack.getVersionCode();
        final int voiceVersion = voicePack.getVersionCode();
        final File langDir = langPack.getInstallationDir(this, langVersion);
        final File voiceDir = voicePack.getInstallationDir(this, voiceVersion);
        deleteRecursive(langDir);
        deleteRecursive(voiceDir);
        if (!langDir.mkdirs() || !voiceDir.mkdirs()) {
            if (BuildConfig.DEBUG)
                Log.e(TAG, "Failed to create target directories");
            return false;
        }
        try (ZipInputStream inStream = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            while ((entry = inStream.getNextEntry()) != null) {
                String name = entry.getName();
                File outFile = null;
                if (name.startsWith("langdata/")) {
                    outFile = new File(langDir, name.substring("langdata/".length()));
                } else if (name.startsWith("data/")) {
                    outFile = new File(voiceDir, name.substring("data/".length()));
                } else {
                    inStream.closeEntry();
                    continue;
                }
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    if (!outFile.getParentFile().exists())
                        outFile.getParentFile().mkdirs();
                    try (OutputStream out = new FileOutputStream(outFile)) {
                        DataPack.copyBytes(inStream, out, null);
                    }
                }
                inStream.closeEntry();
            }
        } catch (IOException e) {
            if (BuildConfig.DEBUG)
                Log.e(TAG, "Failed to extract addon", e);
            deleteRecursive(langDir);
            deleteRecursive(voiceDir);
            return false;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit()
                .putInt(langPack.getVersionKey(), langVersion)
                .putInt(voicePack.getVersionKey(), voiceVersion)
                .putBoolean(String.format("voice.%s.enabled", voicePack.getId()), true)
                .apply();
        LocalAddonStore.saveLanguage(this, new Moshi.Builder().build(), meta.lang);
        return true;
    }

    private Properties readProps(ZipFile zip, String path) throws IOException {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null)
            return null;
        Properties props = new Properties();
        try (InputStream in = zip.getInputStream(entry)) {
            props.load(in);
        }
        return props;
    }

    private int parseInt(String value, int def) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return def;
        }
    }

    private boolean deleteRecursive(File f) {
        if (f == null || !f.exists())
            return true;
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) {
                for (File c : files)
                    deleteRecursive(c);
            }
        }
        return f.delete();
    }

    private String getDefaultTestMessage(String lang2) {
        if ("pl".equalsIgnoreCase(lang2))
            return "Jeśli słyszysz ten komunikat, głos został zainstalowany poprawnie.";
        return "If you can hear this message, the voice installed correctly.";
    }

    private void notifyUser(int resId) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(getApplicationContext(), resId, Toast.LENGTH_LONG).show());
    }

    private static final class AddonMeta {
        final LanguageResource lang;
        final VoiceResource voice;

        AddonMeta(LanguageResource lang, VoiceResource voice) {
            this.lang = lang;
            this.voice = voice;
        }
    }
}

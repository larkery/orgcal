package com.larkery.simpleorgsync;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.widget.Toast;

import com.larkery.simpleorgsync.lib.Application;

import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.Date;

public class PrefsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Fragment frag = new OrgPreferenceFragment();

        getFragmentManager()
                .beginTransaction()
                .replace(android.R.id.content, frag).commit();
    }

    @Override
    protected void onResume() {
        super.onResume();
        long lastSyncTime = ((Application) getApplication()).getLastSyncTime();
        Toast.makeText(
                getApplicationContext(),
                "Last sync at " + (new SimpleDateFormat().format(new Date(lastSyncTime)))
                ,
                Toast.LENGTH_SHORT
        ).show();
    }

    public static class ListIntentMediator extends DialogFragment {
        private String title;
        private  String[] labels;
        private  Intent[] intents;
        private  int[] codes;
        private Fragment author;

        public void setArguments(
                final String title,
                final String[] labels, final Intent[] intents, final int[] codes,
                                  final Fragment author) {
            this.title = title;
            this.labels = labels;
            this.intents = intents;
            this.codes = codes;
            this.author = author;
        }
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle(title)
                    .setItems(labels,
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                    author.startActivityForResult(intents[which], codes[which]);
                                }
                            });
            return builder.create();
        }
    }

    public static class OrgPreferenceFragment extends PreferenceFragment {
        private static final int SELECT_CONTACTS_FILE = 1;
        private static final int SELECT_AGENDA_FILE = 2;
        private static final int SELECT_AGENDA_DIRECTORY = 3;
        private long lastSync = 0;

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            addPreferencesFromResource(R.xml.prefs);

            final Preference agendaFiles = findPreference("agenda_files");
            final Preference contactsFile = findPreference("contacts_file");

            agendaFiles.setSummary(agendaFiles.getSharedPreferences().getString("agenda_files", null));
            contactsFile.setSummary(contactsFile.getSharedPreferences().getString("contacts_file", null));

            agendaFiles.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    ListIntentMediator lim = new ListIntentMediator();
                    lim.setArguments(
                            "How is your agenda stored?",
                            new String[] {"In a single file", "In a directory of files"},
                            new Intent[] {
                                    new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION),
                                    new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                            },
                            new int[] {SELECT_AGENDA_FILE, SELECT_AGENDA_DIRECTORY},
                            OrgPreferenceFragment.this
                    );
                    lim.show(getFragmentManager(), "agenda_type");
                    return true;
                }
            });
            contactsFile.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.setType("*/*");
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                    startActivityForResult(intent, SELECT_CONTACTS_FILE);
                    return true;
                }
            });

            final Preference trigger = findPreference("trigger_sync");
            trigger.setOnPreferenceClickListener(
                    new Preference.OnPreferenceClickListener() {
                        @Override
                        public boolean onPreferenceClick(Preference preference) {
                            final Application a = (Application)
                                    OrgPreferenceFragment.this.getActivity().getApplication();
                            a.requestSync("user pressed button in prefs");
                            return true;
                        }
                    }
            );

            requestPermissions(
                    new String[] {

                            Manifest.permission.READ_CALENDAR,
                            Manifest.permission.WRITE_CALENDAR,
                            Manifest.permission.READ_CONTACTS,
                            Manifest.permission.WRITE_CONTACTS,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.READ_EXTERNAL_STORAGE
                    },
                    0
            );
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            String path = null;
            String pref = null;
            switch (requestCode) {
                case SELECT_AGENDA_FILE:
                    getContext().getContentResolver().takePersistableUriPermission(
                            data.getData(),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    );
                    path = data.getData().toString();
                    pref = "agenda_files";
                    break;
                case SELECT_AGENDA_DIRECTORY:
                    getContext().getContentResolver().takePersistableUriPermission(
                            data.getData(),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    );
                    path = data.getData().toString();
                    pref = "agenda_files";
                    break;
                case SELECT_CONTACTS_FILE:
                    getContext().getContentResolver().takePersistableUriPermission(
                            data.getData(),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    );
                    pref = "contacts_file";
                    path = data.getData().toString();
                    break;
            }
            if (pref != null && path != null) {
                final Preference contactsFile = findPreference(pref);
                contactsFile.setSummary(path);
                contactsFile.getEditor().putString(pref, path).commit();
            }
        }
    }
}

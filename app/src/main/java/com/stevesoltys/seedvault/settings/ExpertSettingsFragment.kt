/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.settings

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.mms.ContentType.TEXT_PLAIN
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.permitDiskReads
import com.stevesoltys.seedvault.transport.backup.PackageService
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.sharedViewModel

class ExpertSettingsFragment : PreferenceFragmentCompat() {

    private val viewModel: SettingsViewModel by sharedViewModel()
    private val packageService: PackageService by inject()

    private val createFileLauncher =
        registerForActivityResult(CreateDocument(TEXT_PLAIN)) { uri ->
            viewModel.onLogcatUriReceived(uri)
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        permitDiskReads {
            setPreferencesFromResource(R.xml.settings_expert, rootKey)
        }

        findPreference<Preference>("logcat")?.setOnPreferenceClickListener {
            val versionName = packageService.getVersionName(requireContext().packageName) ?: "ver"
            val timestamp = System.currentTimeMillis()
            val name = "seedvault-$versionName-$timestamp.txt"
            createFileLauncher.launch(name)
            true
        }

        val quotaPreference = findPreference<SwitchPreferenceCompat>(PREF_KEY_UNLIMITED_QUOTA)

        quotaPreference?.setOnPreferenceChangeListener { _, newValue ->
            quotaPreference.isChecked = newValue as Boolean
            true
        }
    }

    override fun onStart() {
        super.onStart()
        activity?.setTitle(R.string.settings_expert_title)
    }
}

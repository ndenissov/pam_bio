/*
 * Copyright 2026 Nikita Denissov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ovh.dep.pam.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate

object PrivacyUtils {
    const val PRIVACY_POLICY_EN_URL = "https://github.com/ndenissov/pam_bio/blob/main/PRIVACY.md"
    const val PRIVACY_POLICY_ZH_URL = "https://github.com/ndenissov/pam_bio/blob/main/PRIVACY_ZH.md"

    fun isChineseLocale(context: Context): Boolean {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        if (!appLocales.isEmpty) {
            val lang = appLocales[0]?.language?.lowercase()
            return lang == "zh"
        }
        val currentLocale = context.resources.configuration.locales[0]
        return currentLocale?.language?.lowercase() == "zh"
    }

    fun getPrivacyPolicyUrl(context: Context): String {
        return if (isChineseLocale(context)) {
            PRIVACY_POLICY_ZH_URL
        } else {
            PRIVACY_POLICY_EN_URL
        }
    }

    fun openPrivacyPolicy(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getPrivacyPolicyUrl(context)))
        context.startActivity(intent)
    }
}

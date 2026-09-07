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

package ovh.dep.pam.ui.about

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.pm.PackageManager
import ovh.dep.pam.R

data class OssLibrary(
    val name: String,
    val license: String,
    val url: String
)

val libraries = listOf(
    OssLibrary("Jetpack Compose", "Apache 2.0", "https://developer.android.com/jetpack/compose"),
    OssLibrary("Kotlinx Coroutines", "Apache 2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
    OssLibrary("Kotlinx Serialization", "Apache 2.0", "https://github.com/Kotlin/kotlinx.serialization"),
    OssLibrary("Jetpack DataStore", "Apache 2.0", "https://developer.android.com/topic/libraries/architecture/datastore"),
    OssLibrary("ZXing Android Embedded", "Apache 2.0", "https://github.com/journeyapps/zxing-android-embedded"),
    OssLibrary("Bouncy Castle", "MIT", "https://www.bouncycastle.org/")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Filled.Code,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    val packageInfo = try {
                        context.packageManager.getPackageInfo(context.packageName, 0)
                    } catch (e: PackageManager.NameNotFoundException) {
                        null
                    }
                    val versionName = packageInfo?.versionName ?: "Unknown"

                    Text(
                        text = stringResource(R.string.app_version, versionName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.with_love),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ndenissov/pam_bio"))
                            context.startActivity(intent)
                        }
                    ) {
                        Text(stringResource(R.string.github_repo))
                    }
                }
                
                Divider()
                
                Text(
                    text = "Open Source Licenses",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp, end = 16.dp)
                )
            }
            
            items(libraries) { lib ->
                LibraryItem(lib = lib)
            }
        }
    }
}

@Composable
fun LibraryItem(lib: OssLibrary) {
    val context = LocalContext.current
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(lib.url))
                context.startActivity(intent)
            }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = lib.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = lib.license,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

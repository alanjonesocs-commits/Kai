import os
import sys

target_file = None
for root, dirs, files in os.walk("."):
    if "MainActivity.kt" in files:
        target_file = os.path.join(root, "MainActivity.kt")
        break

if not target_file:
    print("Error: Could not find MainActivity.kt")
    sys.exit(1)

with open(target_file, "r") as f:
    content = f.read()

imports = """
import android.content.Context
import java.io.File
import com.inspiredandroid.kai.nia.memory.MemBundle
import com.inspiredandroid.kai.nia.memory.PamManifest
import com.inspiredandroid.kai.nia.memory.kaiJsonToMemBundle
import com.inspiredandroid.kai.nia.memory.memBundleToKaiJson
"""

if "import android.content.Context" not in content:
    content = content.replace("package com.inspiredandroid.kai", "package com.inspiredandroid.kai\n" + imports)

methods = """
    override fun onStop() {
        super.onStop()
        try {
            val prefs = getSharedPreferences("kai_settings", Context.MODE_PRIVATE)
            val memoryJson = prefs.getString("agent_memories", prefs.getString("memory", "{}")) ?: "{}"
            val nowIso = java.time.Instant.now().toString()
            val bundle = kaiJsonToMemBundle(memoryJson, nowIso)
            val memString = bundle.toJsonLines()
            val dir = getExternalFilesDir(null)
            if (dir != null) {
                val file = File(dir, "nia-memory.mem")
                file.writeText(memString)
            }
        } catch (e: Exception) {}
    }

    override fun onStart() {
        super.onStart()
        try {
            val dir = getExternalFilesDir(null)
            if (dir != null) {
                val file = File(dir, "nia-memory.mem")
                if (file.exists()) {
                    val text = file.readText()
                    val episodes = MemBundle.parseEpisodes(text)
                    val bundle = MemBundle(PamManifest("load", java.time.Instant.now().toString(), 1, ""), episodes)
                    val prefs = getSharedPreferences("kai_settings", Context.MODE_PRIVATE)
                    val currentJson = prefs.getString("agent_memories", "{}") ?: "{}"
                    val newJson = memBundleToKaiJson(bundle, currentJson)
                    prefs.edit().putString("agent_memories", newJson).apply()
                }
            }
        } catch (e: Exception) {}
    }
"""

if "override fun onStop()" not in content:
    last_brace = content.rfind("}")
    content = content[:last_brace] + methods + content[last_brace:]
    with open(target_file, "w") as f:
        f.write(content)
    print("Success! Auto-Sync and Auto-Load injected into " + target_file)
else:
    print("Notice: Methods already exist in " + target_file)

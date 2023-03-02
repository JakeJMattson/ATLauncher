/*
 * ATLauncher - https://github.com/ATLauncher/ATLauncher
 * Copyright (C) 2013-2022 ATLauncher
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.atlauncher

import com.atlauncher.utils.OS
import com.atlauncher.utils.Utils
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val launcherPath = args[0]
    val temporaryUpdatePath = args[1]
    val otherArgs = args.copyOfRange(2, args.size)
    val launcher = File(launcherPath)
    val temporaryUpdate = File(temporaryUpdatePath)

    Utils.copyFile(temporaryUpdate, launcher.parentFile)
    launcher.setExecutable(true)

    val arguments = buildList {
        if (OS.isMac() && OS.isUsingMacApp()) {
            add("open")
            add("-n")
            add(FileSystem.BASE_DIR.parent.parent.toAbsolutePath().toString())
            add("--args")
        } else {
            val path = System.getProperty("java.home") +
                "${File.separator}bin${File.separator}java" +
                if (OS.isWindows()) "w" else ""

            add(path)
            add("-Djna.nosys=true")
            add("-jar")
            add(launcherPath)
        }

        add("--updated=true")
        addAll(listOf(*otherArgs))
    }

    with(ProcessBuilder()) {
        directory(FileSystem.BASE_DIR.toFile())
        command(arguments)
        start()
    }

    exitProcess(0)
}
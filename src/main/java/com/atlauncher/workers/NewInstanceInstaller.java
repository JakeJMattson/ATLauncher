/*
 * ATLauncher - https://github.com/ATLauncher/ATLauncher
 * Copyright (C) 2013-2019 ATLauncher
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
package com.atlauncher.workers;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.atlauncher.App;
import com.atlauncher.Gsons;
import com.atlauncher.LogManager;
import com.atlauncher.Network;
import com.atlauncher.data.Constants;
import com.atlauncher.data.Language;
import com.atlauncher.data.json.Delete;
import com.atlauncher.data.json.Deletes;
import com.atlauncher.data.json.DownloadType;
import com.atlauncher.data.minecraft.ArgumentRule;
import com.atlauncher.data.minecraft.Arguments;
import com.atlauncher.data.minecraft.AssetIndex;
import com.atlauncher.data.minecraft.AssetObject;
import com.atlauncher.data.minecraft.Download;
import com.atlauncher.data.minecraft.Downloads;
import com.atlauncher.data.minecraft.Library;
import com.atlauncher.data.minecraft.LoggingFile;
import com.atlauncher.data.minecraft.MinecraftVersion;
import com.atlauncher.data.minecraft.MojangAssetIndex;
import com.atlauncher.data.minecraft.MojangDownload;
import com.atlauncher.data.minecraft.MojangDownloads;
import com.atlauncher.data.minecraft.VersionManifest;
import com.atlauncher.data.minecraft.VersionManifestVersion;
import com.atlauncher.data.minecraft.loaders.Loader;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;
import com.atlauncher.data.minecraft.loaders.forge.ForgeLibrary;
import com.atlauncher.network.DownloadPool;
import com.atlauncher.utils.FileUtils;
import com.atlauncher.utils.Utils;
import com.atlauncher.utils.walker.CaseFileVisitor;

import org.zeroturnaround.zip.NameMapper;
import org.zeroturnaround.zip.ZipUtil;

import okhttp3.OkHttpClient;

public class NewInstanceInstaller extends InstanceInstaller {
    protected double percent = 0.0; // Percent done installing
    protected double subPercent = 0.0; // Percent done sub installing
    protected double totalBytes = 0; // Total number of bytes to download
    protected double downloadedBytes = 0; // Total number of bytes downloaded

    public final String instanceName;
    public final com.atlauncher.data.Pack pack;
    public final com.atlauncher.data.PackVersion version;
    public final String shareCode;
    public final boolean showModsChooser;
    public final LoaderVersion loaderVersion;

    public boolean isReinstall;
    public boolean isServer;

    public final Path root;
    public final Path temp;

    public List<Library> libraries = new ArrayList<>();
    public Loader loader;
    public com.atlauncher.data.json.Version packVersion;
    public MinecraftVersion minecraftVersion;

    public boolean assetsMapToResources = false;

    private boolean savedReis = false; // If Reis Minimap stuff was found and saved
    private boolean savedZans = false; // If Zans Minimap stuff was found and saved
    private boolean savedNEICfg = false; // If NEI Config was found and saved
    private boolean savedOptionsTxt = false; // If options.txt was found and saved
    private boolean savedServersDat = false; // If servers.dat was found and saved
    private boolean savedPortalGunSounds = false; // If Portal Gun Sounds was found and saved

    public String mainClass;
    public Arguments arguments;

    public NewInstanceInstaller(String instanceName, com.atlauncher.data.Pack pack,
            com.atlauncher.data.PackVersion version, boolean isReinstall, boolean isServer, String shareCode,
            boolean showModsChooser, com.atlauncher.data.loaders.LoaderVersion loaderVersion) {
        super(instanceName, pack, version, isReinstall, isServer, shareCode, showModsChooser, loaderVersion);

        this.instanceName = instanceName;
        this.pack = pack;
        this.version = version;
        this.isReinstall = isReinstall;
        this.isServer = isServer;
        this.shareCode = shareCode;
        this.showModsChooser = showModsChooser;

        if (isServer) {
            this.root = new File(App.settings.getServersDir(), pack.getSafeName() + "_" + version.getSafeVersion())
                    .toPath();
        } else {
            this.root = new File(App.settings.getInstancesDir(), getInstanceSafeName()).toPath();
        }

        this.temp = new File(App.settings.getTempDir(), pack.getSafeName() + "_" + version.getSafeVersion()).toPath();

        this.loaderVersion = Gsons.MINECRAFT.fromJson(Gsons.DEFAULT.toJson(loaderVersion), LoaderVersion.class);
    }

    public NewInstanceInstaller(String instanceName, com.atlauncher.data.Pack pack,
            com.atlauncher.data.PackVersion version, boolean isReinstall, boolean isServer, String shareCode,
            boolean showModsChooser, LoaderVersion loaderVersion) {
        super(instanceName, pack, version, isReinstall, isServer, shareCode, showModsChooser, null);

        this.instanceName = instanceName;
        this.pack = pack;
        this.version = version;
        this.isReinstall = isReinstall;
        this.isServer = isServer;
        this.shareCode = shareCode;
        this.showModsChooser = showModsChooser;

        if (isServer) {
            this.root = new File(App.settings.getServersDir(), pack.getSafeName() + "_" + version.getSafeVersion())
                    .toPath();
        } else {
            this.root = new File(App.settings.getInstancesDir(), getInstanceSafeName()).toPath();
        }

        this.temp = new File(App.settings.getTempDir(), pack.getSafeName() + "_" + version.getSafeVersion()).toPath();

        this.loaderVersion = loaderVersion;
    }

    @Override
    protected Boolean doInBackground() throws Exception {
        LogManager.info("Started install of " + this.pack.getName() + " - " + this.version);

        try {
            downloadPackVersionJson();

            downloadMinecraftVersionJson();

            if (this.packVersion.loader != null) {
                this.loader = this.packVersion.getLoader().getNewLoader(new File(this.getTempDirectory(), "loader"),
                        this, this.loaderVersion);

                downloadLoader();
            }

            if (this.packVersion.messages != null) {
                showMessages();
            }

            determineModsToBeInstalled();

            install();

            return true;
        } catch (Exception e) {
            Network.CLIENT.dispatcher().executorService().shutdown();
            Network.CLIENT.connectionPool().evictAll();
            cancel(true);
            LogManager.logStackTrace(e);
        }

        return false;
    }

    private void downloadPackVersionJson() {
        addPercent(5);
        fireTask(Language.INSTANCE.localize("instance.downloadingpackverisondefinition"));
        fireSubProgressUnknown();

        this.packVersion = com.atlauncher.network.Download.build()
                .setUrl(this.pack.getJsonDownloadUrl(version.getVersion()))
                .asClass(com.atlauncher.data.json.Version.class);

        this.packVersion.compileColours();

        hideSubProgressBar();
    }

    private void downloadMinecraftVersionJson() throws Exception {
        addPercent(5);
        fireTask(Language.INSTANCE.localize("instance.downloadingminecraftdefinition"));
        fireSubProgressUnknown();

        VersionManifest versionManifest = com.atlauncher.network.Download.build()
                .setUrl(String.format("%s/mc/game/version_manifest.json", Constants.LAUNCHER_META_MINECRAFT))
                .asClass(VersionManifest.class);

        VersionManifestVersion minecraftVersion = versionManifest.versions.stream()
                .filter(version -> version.id.equalsIgnoreCase(this.packVersion.getMinecraft())).findFirst()
                .orElse(null);

        if (minecraftVersion == null) {
            throw new Exception(
                    String.format("Failed to find Minecraft version of %s", this.packVersion.getMinecraft()));
        }

        this.minecraftVersion = com.atlauncher.network.Download.build().setUrl(minecraftVersion.url)
                .asClass(MinecraftVersion.class);

        hideSubProgressBar();
    }

    private void downloadLoader() {
        addPercent(5);
        fireTask(Language.INSTANCE.localize("instance.downloadingloader"));
        fireSubProgressUnknown();

        this.loader.downloadAndExtractInstaller();

        hideSubProgressBar();
    }

    private void showMessages() throws Exception {
        int ret = 0;

        if (this.isReinstall && this.packVersion.messages.update != null) {
            ret = this.packVersion.messages.showUpdateMessage(this.pack);
        } else if (this.packVersion.messages.install != null) {
            ret = this.packVersion.messages.showInstallMessage(this.pack);
        }

        if (ret != 0) {
            throw new Exception("Install cancelled after viewing message!");
        }
    }

    private void determineModsToBeInstalled() {
        this.allMods = sortMods(
                (this.isServer ? this.packVersion.getServerInstallMods() : this.packVersion.getClientInstallMods()));

        boolean hasOptional = this.allMods.stream().anyMatch(mod -> mod.isOptional());

        if (this.allMods.size() != 0 && hasOptional) {
            com.atlauncher.gui.dialogs.ModsChooser modsChooser = new com.atlauncher.gui.dialogs.ModsChooser(this);

            if (this.shareCode != null) {
                modsChooser.applyShareCode(shareCode);
            }

            if (this.showModsChooser) {
                modsChooser.setVisible(true);
            }

            if (modsChooser.wasClosed()) {
                this.cancel(true);
                return;
            }
            this.selectedMods = modsChooser.getSelectedMods();
            this.unselectedMods = modsChooser.getUnselectedMods();
        }

        if (!hasOptional) {
            this.selectedMods = this.allMods;
        }

        modsInstalled = new ArrayList<>();
        for (com.atlauncher.data.json.Mod mod : this.selectedMods) {
            String file = mod.getFile();
            if (this.packVersion.getCaseAllFiles() == com.atlauncher.data.json.CaseType.upper) {
                file = file.substring(0, file.lastIndexOf(".")).toUpperCase() + file.substring(file.lastIndexOf("."));
            } else if (this.packVersion.getCaseAllFiles() == com.atlauncher.data.json.CaseType.lower) {
                file = file.substring(0, file.lastIndexOf(".")).toLowerCase() + file.substring(file.lastIndexOf("."));
            }
            this.modsInstalled
                    .add(new com.atlauncher.data.DisableableMod(mod.getName(), mod.getVersion(), mod.isOptional(), file,
                            com.atlauncher.data.Type.valueOf(com.atlauncher.data.Type.class, mod.getType().toString()),
                            this.packVersion.getColour(mod.getColour()), mod.getDescription(), false, false, true,
                            mod.getCurseModId(), mod.getCurseFileId()));
        }

        if (this.isReinstall && instance.hasCustomMods()
                && instance.getMinecraftVersion().equalsIgnoreCase(version.getMinecraftVersion().getVersion())) {
            for (com.atlauncher.data.DisableableMod mod : instance.getCustomDisableableMods()) {
                modsInstalled.add(mod);
            }
        }
    }

    private Boolean install() throws Exception {
        this.instanceIsCorrupt = true; // From this point on the instance has become corrupt

        getTempDirectory().mkdirs(); // Make the temp directory
        backupSelectFiles();
        prepareFilesystem();
        addPercent(5);

        determineMainClass();
        determineArguments();

        downloadResources();
        if (isCancelled()) {
            return false;
        }

        downloadMinecraft();
        if (isCancelled()) {
            return false;
        }

        downloadLoggingClient();
        if (isCancelled()) {
            return false;
        }

        downloadLibraries();
        if (isCancelled()) {
            return false;
        }

        organiseLibraries();
        if (isCancelled()) {
            return false;
        }

        installLoader();
        if (isCancelled()) {
            return false;
        }

        downloadMods();
        if (isCancelled()) {
            return false;
        }

        installMods();
        if (isCancelled()) {
            return false;
        }

        runCaseConversion();
        if (isCancelled()) {
            return false;
        }

        runActions();
        if (isCancelled()) {
            return false;
        }

        installConfigs();
        if (isCancelled()) {
            return false;
        }

        // Copy over common configs if any
        if (App.settings.getCommonConfigsDir().listFiles().length != 0) {
            Utils.copyDirectory(App.settings.getCommonConfigsDir(), getRootDirectory());
        }

        restoreSelectFiles();

        installServerBootScripts();

        return true;
    }

    private void determineMainClass() {
        if (this.packVersion.mainClass != null) {
            if (this.packVersion.mainClass.depends == null && this.packVersion.mainClass.dependsGroup == null) {
                this.mainClass = this.packVersion.mainClass.mainClass;
            } else if (this.packVersion.mainClass.depends != null) {
                String depends = this.packVersion.mainClass.depends;

                if (this.selectedMods.stream().filter(mod -> mod.name.equalsIgnoreCase(depends)).count() != 0) {
                    this.mainClass = this.packVersion.mainClass.mainClass;
                }
            } else if (this.packVersion.getMainClass().hasDependsGroup()) {
                String dependsGroup = this.packVersion.mainClass.dependsGroup;

                if (this.selectedMods.stream().filter(mod -> mod.group.equalsIgnoreCase(dependsGroup)).count() != 0) {
                    this.mainClass = this.packVersion.mainClass.mainClass;
                }
            }
        }

        // use the loader provided main class if there is a loader
        if (this.loader != null) {
            this.mainClass = this.loader.getMainClass();
        }

        // if none set by pack, then use the minecraft one
        if (this.mainClass == null) {
            this.mainClass = this.version.getMinecraftVersion().getMojangVersion().getMainClass();
        }
    }

    private void determineArguments() {
        this.arguments = new Arguments();

        if (this.loader != null) {
            if (this.loader.useMinecraftArguments()) {
                addMinecraftArguments();
            }

            Arguments loaderArguments = this.loader.getArguments();

            if (loaderArguments != null) {
                if (loaderArguments.game != null && loaderArguments.game.size() != 0) {
                    this.arguments.game.addAll(loaderArguments.game);
                }

                if (loaderArguments.jvm != null && loaderArguments.jvm.size() != 0) {
                    this.arguments.jvm.addAll(loaderArguments.jvm);
                }
            }
        } else {
            addMinecraftArguments();
        }

        if (this.packVersion.extraArguments != null) {
            boolean add = false;

            if (this.packVersion.extraArguments.depends == null
                    && this.packVersion.extraArguments.dependsGroup == null) {
                add = true;
            } else if (this.packVersion.extraArguments.depends == null) {
                String depends = this.packVersion.extraArguments.depends;

                if (this.selectedMods.stream().filter(mod -> mod.name.equalsIgnoreCase(depends)).count() != 0) {
                    add = true;
                }
            } else if (this.packVersion.extraArguments.dependsGroup == null) {
                String dependsGroup = this.packVersion.extraArguments.dependsGroup;

                if (this.selectedMods.stream().filter(mod -> mod.group.equalsIgnoreCase(dependsGroup)).count() != 0) {
                    add = true;
                }
            }

            if (add) {
                this.arguments.game.addAll(Arrays.asList(this.packVersion.extraArguments.arguments.split(" ")).stream()
                        .map(argument -> new ArgumentRule(argument)).collect(Collectors.toList()));
            }
        }
    }

    private void addMinecraftArguments() {
        // older MC versions
        if (this.minecraftVersion.minecraftArguments != null) {
            this.arguments.game.addAll(Arrays.asList(this.minecraftVersion.minecraftArguments.split(" ")).stream()
                    .map(arg -> new ArgumentRule(null, arg)).collect(Collectors.toList()));
        }

        // newer MC versions
        if (this.minecraftVersion.arguments != null) {
            if (this.minecraftVersion.arguments.game != null && this.minecraftVersion.arguments.game.size() != 0) {
                this.arguments.game.addAll(this.minecraftVersion.arguments.game);
            }

            if (this.minecraftVersion.arguments.jvm != null && this.minecraftVersion.arguments.jvm.size() != 0) {
                this.arguments.jvm.addAll(this.minecraftVersion.arguments.jvm);
            }
        }
    }

    protected void downloadResources() throws Exception {
        addPercent(5);

        if (this.isServer || this.minecraftVersion.assetIndex == null) {
            return;
        }

        fireTask(Language.INSTANCE.localize("instance.downloadingresources"));
        fireSubProgressUnknown();
        this.totalBytes = this.downloadedBytes = 0;

        MojangAssetIndex assetIndex = this.minecraftVersion.assetIndex;

        AssetIndex index = com.atlauncher.network.Download.build().setUrl(assetIndex.url).hash(assetIndex.sha1)
                .size(assetIndex.size)
                .downloadTo(new File(App.settings.getIndexesAssetsDir(), assetIndex.id + ".json").toPath())
                .asClass(AssetIndex.class);

        if (index.mapToResources) {
            this.assetsMapToResources = true;
        }

        OkHttpClient httpClient = Network.createProgressClient(this);
        DownloadPool pool = new DownloadPool();

        index.objects.entrySet().stream().forEach(entry -> {
            AssetObject object = entry.getValue();
            String filename = object.hash.substring(0, 2) + "/" + object.hash;
            String url = String.format("%s/%s", Constants.MINECRAFT_RESOURCES, filename);
            File file = new File(App.settings.getObjectsAssetsDir(), filename);

            com.atlauncher.network.Download download = new com.atlauncher.network.Download().setUrl(url)
                    .downloadTo(file.toPath()).hash(object.hash).size(object.size).withInstanceInstaller(this)
                    .withHttpClient(httpClient).withFriendlyFileName(entry.getKey());

            if (index.mapToResources) {
                download = download
                        .copyTo(new File(new File(this.getRootDirectory(), "resources"), entry.getKey()).toPath());
            }

            pool.add(download);
        });

        DownloadPool smallPool = pool.downsize();

        this.setTotalBytes(smallPool.totalSize());
        this.fireSubProgress(0);

        smallPool.downloadAll(this);

        hideSubProgressBar();
    }

    private void downloadMinecraft() throws Exception {
        addPercent(5);
        fireTask(Language.INSTANCE.localize("instance.downloadingminecraft"));
        fireSubProgressUnknown();
        totalBytes = 0;
        downloadedBytes = 0;

        MojangDownloads downloads = this.minecraftVersion.downloads;

        MojangDownload mojangDownload = this.isServer ? downloads.server : downloads.client;

        com.atlauncher.network.Download.build().setUrl(mojangDownload.url).hash(mojangDownload.sha1)
                .size(mojangDownload.size).downloadTo(getMinecraftJarLibrary().toPath())
                .copyTo(this.isServer ? getMinecraftJar().toPath() : null).withInstanceInstaller(this)
                .withHttpClient(Network.createProgressClient(this)).downloadFile();

        setTotalBytes(mojangDownload.size);

        hideSubProgressBar();
    }

    public File getMinecraftJar() {
        if (isServer) {
            return new File(getRootDirectory(), String.format("minecraft_server.%s.jar", this.minecraftVersion.id));
        }

        return new File(getRootDirectory(), String.format("%s.jar", this.minecraftVersion.id));
    }

    private void downloadLoggingClient() throws Exception {
        addPercent(5);

        if (this.isServer || this.minecraftVersion.logging == null) {
            return;
        }

        fireTask(Language.INSTANCE.localize("instance.downloadingloggingconfig"));
        fireSubProgressUnknown();

        LoggingFile loggingFile = this.minecraftVersion.logging.client.file;

        com.atlauncher.network.Download.build().setUrl(loggingFile.url).hash(loggingFile.sha1).size(loggingFile.size)
                .downloadTo(new File(App.settings.getLogConfigsDir(), loggingFile.id).toPath()).downloadFile();

        hideSubProgressBar();
    }

    private List<Library> getLibraries() {
        List<Library> libraries = new ArrayList<>();

        List<Library> packVersionLibraries = getPackVersionLibraries();

        if (packVersionLibraries != null && packVersionLibraries.size() != 0) {
            libraries.addAll(packVersionLibraries);
        }

        // Now read in the library jars needed from the loader
        if (this.loader != null) {
            libraries.addAll(this.loader.getLibraries());
        }

        // lastly the Minecraft libraries
        if (this.loader == null || this.loader.useMinecraftLibraries()) {
            libraries.addAll(this.minecraftVersion.libraries.stream().filter(library -> library.shouldInstall())
                    .collect(Collectors.toList()));
        }

        return libraries;
    }

    public String getMinecraftJarLibraryPath() {
        return getMinecraftJarLibraryPath(isServer ? "server" : "client");
    }

    public String getMinecraftJarLibraryPath(String type) {
        return "net/minecraft/" + type + "/" + this.minecraftVersion.id + "/" + type + "-" + this.minecraftVersion.id
                + ".jar".replace("/", File.separatorChar + "");
    }

    public List<String> getLibrariesForLaunch() {
        List<String> libraries = new ArrayList<>();

        libraries.add(this.getMinecraftJarLibraryPath());

        libraries.addAll(this.getLibraries().stream()
                .filter(library -> library.downloads.artifact != null && library.downloads.artifact.path != null)
                .map(library -> library.downloads.artifact.path).collect(Collectors.toList()));

        return libraries;
    }

    public String getMinecraftArguments() {
        return this.arguments.asString();
    }

    public boolean doAssetsMapToResources() {
        return this.assetsMapToResources;
    }

    private List<Library> getPackVersionLibraries() {
        List<Library> libraries = new ArrayList<>();

        // Now read in the library jars needed from the pack
        for (com.atlauncher.data.json.Library library : this.packVersion.getLibraries()) {
            if (this.isServer && !library.forServer()) {
                continue;
            }

            if (library.depends != null) {
                if (this.selectedMods.stream().filter(mod -> mod.name.equalsIgnoreCase(library.depends)).count() == 0) {
                    continue;
                }
            } else if (library.hasDependsGroup()) {
                if (this.selectedMods.stream().filter(mod -> mod.group.equalsIgnoreCase(library.dependsGroup))
                        .count() == 0) {
                    continue;
                }
            }

            Library minecraftLibrary = new Library();

            minecraftLibrary.name = library.file;

            Download download = new Download();
            download.path = library.path != null ? library.path
                    : (library.server != null ? library.server : library.file);
            download.sha1 = library.md5;
            download.size = library.filesize;
            download.url = String.format("%s/%s", Constants.ATLAUNCHER_DOWNLOAD_SERVER, library.url);

            Downloads downloads = new Downloads();
            downloads.artifact = download;

            minecraftLibrary.downloads = downloads;

            libraries.add(minecraftLibrary);
        }

        return libraries;
    }

    private void downloadLibraries() {
        addPercent(5);
        fireTask(Language.INSTANCE.localize("instance.downloadinglibraries"));
        fireSubProgressUnknown();

        OkHttpClient httpClient = Network.createProgressClient(this);
        DownloadPool pool = new DownloadPool();

        this.getLibraries().stream().filter(library -> library.shouldInstall() && library.downloads.artifact != null)
                .forEach(library -> {
                    com.atlauncher.network.Download download = new com.atlauncher.network.Download()
                            .setUrl(library.downloads.artifact.url)
                            .downloadTo(new File(App.settings.getGameLibrariesDir(), library.downloads.artifact.path)
                                    .toPath())
                            .hash(library.downloads.artifact.sha1).size(library.downloads.artifact.size)
                            .withInstanceInstaller(this).withHttpClient(httpClient);

                    if (library instanceof ForgeLibrary && ((ForgeLibrary) library).isUsingPackXz()) {
                        download = download.usesPackXz(((ForgeLibrary) library).checksums);
                    }

                    pool.add(download);
                });

        if (this.loader != null && this.loader.getInstallLibraries() != null) {
            this.loader.getInstallLibraries().stream().filter(library -> library.downloads.artifact != null)
                    .forEach(library -> {
                        pool.add(
                                new com.atlauncher.network.Download().setUrl(library.downloads.artifact.url)
                                        .downloadTo(new File(App.settings.getGameLibrariesDir(),
                                                library.downloads.artifact.path).toPath())
                                        .hash(library.downloads.artifact.sha1).size(library.downloads.artifact.size)
                                        .withInstanceInstaller(this).withHttpClient(httpClient));
                    });
        }

        this.getLibraries().stream().filter(library -> library.hasNativeForOS()).forEach(library -> {
            Download download = library.getNativeDownloadForOS();

            pool.add(new com.atlauncher.network.Download().setUrl(download.url)
                    .downloadTo(new File(App.settings.getGameLibrariesDir(), download.path).toPath())
                    .hash(download.sha1).size(download.size).withInstanceInstaller(this).withHttpClient(httpClient));
        });

        DownloadPool smallPool = pool.downsize();

        this.setTotalBytes(smallPool.totalSize());
        this.fireSubProgress(0);

        smallPool.downloadAll(this);

        hideSubProgressBar();
    }

    private void organiseLibraries() {
        addPercent(5);
        fireTask(Language.INSTANCE.localize("instance.organisinglibraries"));
        fireSubProgressUnknown();

        this.getLibraries().stream().filter(library -> library.shouldInstall()).forEach(library -> {
            if (isServer && library.downloads.artifact != null) {
                File libraryFile = new File(App.settings.getGameLibrariesDir(), library.downloads.artifact.path);

                File serverFile = new File(getLibrariesDirectory(), library.downloads.artifact.path);

                serverFile.getParentFile().mkdirs();

                Utils.copyFile(libraryFile, serverFile, true);
            } else if (library.hasNativeForOS()) {
                File nativeFile = new File(App.settings.getGameLibrariesDir(), library.getNativeDownloadForOS().path);

                ZipUtil.unpack(nativeFile, this.getNativesDirectory(), new NameMapper() {
                    public String map(String name) {
                        if (library.extract != null && library.extract.shouldExclude(name)) {
                            return null;
                        }

                        return name;
                    }
                });
            }
        });

        if (this.loader != null && this.loader.getInstallLibraries() != null) {
            this.loader.getInstallLibraries().stream().filter(library -> library.downloads.artifact != null)
                    .forEach(library -> {
                        if (isServer) {
                            File libraryFile = new File(App.settings.getGameLibrariesDir(),
                                    library.downloads.artifact.path);

                            File serverFile = new File(getLibrariesDirectory(), library.downloads.artifact.path);

                            serverFile.getParentFile().mkdirs();

                            Utils.copyFile(libraryFile, serverFile, true);
                        }
                    });
        }

        if (this.loader != null && isServer) {
            Library forgeLibrary = this.loader.getLibraries().stream()
                    .filter(library -> library.name.startsWith("net.minecraftforge:forge")).findFirst().orElse(null);

            if (forgeLibrary != null) {
                File extractedLibraryFile = new File(App.settings.getGameLibrariesDir(),
                        forgeLibrary.downloads.artifact.path);
                Utils.copyFile(extractedLibraryFile, new File(this.root.toFile(), this.loader.getServerJar()), true);
            }
        }

        hideSubProgressBar();
    }

    private void installLoader() {
        addPercent(5);

        if (this.loader == null) {
            return;
        }

        fireTask(Language.INSTANCE.localize("instance.installingloader"));
        fireSubProgressUnknown();

        // run any processors that the loader needs
        this.loader.runProcessors();

        hideSubProgressBar();
    }

    private void downloadMods() {
        addPercent(25);

        if (selectedMods.size() == 0) {
            return;
        }

        fireTask(Language.INSTANCE.localize("instance.downloadingmods"));
        fireSubProgressUnknown();

        OkHttpClient httpClient = Network.createProgressClient(this);
        DownloadPool pool = new DownloadPool();

        this.selectedMods.stream().filter(mod -> mod.download != DownloadType.browser).forEach(mod -> {
            pool.add(new com.atlauncher.network.Download().setUrl(mod.getDownloadUrl())
                    .downloadTo(new File(App.settings.getDownloadsDir(), mod.getFile()).toPath()).hash(mod.md5)
                    .size(mod.filesize).withInstanceInstaller(this).withHttpClient(httpClient));
        });

        DownloadPool smallPool = pool.downsize();

        this.setTotalBytes(smallPool.totalSize());
        this.fireSubProgress(0);

        smallPool.downloadAll(this);

        fireSubProgressUnknown();

        this.selectedMods.stream().filter(mod -> mod.download == DownloadType.browser).forEach(mod -> {
            mod.download(this);
        });

        hideSubProgressBar();
    }

    private void installMods() {
        addPercent(25);

        if (this.selectedMods.size() == 0) {
            return;
        }

        fireTask(Language.INSTANCE.localize("instance.installingmods"));
        fireSubProgressUnknown();

        double subPercentPerMod = 100.0 / this.selectedMods.size();

        this.selectedMods.parallelStream().forEach(mod -> {
            mod.install(this);
            addSubPercent(subPercentPerMod);
        });

        hideSubProgressBar();
    }

    private void runCaseConversion() throws Exception {
        addPercent(5);

        if (this.packVersion.caseAllFiles == null) {
            return;
        }

        if (this.isReinstall && this.instance.getMinecraftVersion().equalsIgnoreCase(this.minecraftVersion.id)) {
            Files.walkFileTree(this.getModsDirectory().toPath(), new CaseFileVisitor(this.packVersion.caseAllFiles,
                    this.instance.getCustomMods(com.atlauncher.data.Type.mods)));
        } else {
            Files.walkFileTree(this.getModsDirectory().toPath(), new CaseFileVisitor(this.packVersion.caseAllFiles));
        }
    }

    private void runActions() {
        addPercent(5);

        if (this.packVersion.actions == null || this.packVersion.actions.size() == 0) {
            return;
        }

        for (com.atlauncher.data.json.Action action : this.packVersion.actions) {
            action.execute(this);
        }
    }

    private void installConfigs() throws Exception {
        addPercent(5);

        if (this.packVersion.noConfigs) {
            return;
        }

        fireTask(Language.INSTANCE.localize("instance.downloadingconfigs"));

        File configs = new File(App.settings.getTempDir(), "Configs.zip");
        String path = "packs/" + pack.getSafeName() + "/versions/" + version.getVersion() + "/Configs.zip";

        com.atlauncher.network.Download configsDownload = com.atlauncher.network.Download.build()
                .setUrl(String.format("%s/%s", Constants.ATLAUNCHER_DOWNLOAD_SERVER, path)).downloadTo(configs.toPath())
                .withInstanceInstaller(this).withHttpClient(Network.createProgressClient(this));

        this.setTotalBytes(configsDownload.getFilesize());
        configsDownload.downloadFile();

        if (!configs.exists()) {
            throw new Exception("Failed to download configs for pack!");
        }

        fireSubProgressUnknown();
        fireTask(Language.INSTANCE.localize("instance.extractingconfigs"));

        Utils.unzip(configs, getRootDirectory());
        Utils.delete(configs);
    }

    private void backupSelectFiles() {
        File reis = new File(getModsDirectory(), "rei_minimap");
        if (reis.exists() && reis.isDirectory()) {
            if (Utils.copyDirectory(reis, getTempDirectory(), true)) {
                savedReis = true;
            }
        }

        File zans = new File(getModsDirectory(), "VoxelMods");
        if (zans.exists() && zans.isDirectory()) {
            if (Utils.copyDirectory(zans, getTempDirectory(), true)) {
                savedZans = true;
            }
        }

        File neiCfg = new File(getConfigDirectory(), "NEI.cfg");
        if (neiCfg.exists() && neiCfg.isFile()) {
            if (Utils.copyFile(neiCfg, getTempDirectory())) {
                savedNEICfg = true;
            }
        }

        File optionsTXT = new File(getRootDirectory(), "options.txt");
        if (optionsTXT.exists() && optionsTXT.isFile()) {
            if (Utils.copyFile(optionsTXT, getTempDirectory())) {
                savedOptionsTxt = true;
            }
        }

        File serversDAT = new File(getRootDirectory(), "servers.dat");
        if (serversDAT.exists() && serversDAT.isFile()) {
            if (Utils.copyFile(serversDAT, getTempDirectory())) {
                savedServersDat = true;
            }
        }

        File portalGunSounds = new File(getModsDirectory(), "PortalGunSounds.pak");
        if (portalGunSounds.exists() && portalGunSounds.isFile()) {
            savedPortalGunSounds = true;
            Utils.copyFile(portalGunSounds, getTempDirectory());
        }
    }

    protected void prepareFilesystem() throws Exception {
        if (isReinstall || isServer) {
            FileUtils.deleteDirectory(this.root.resolve("bin"));
            FileUtils.deleteDirectory(this.root.resolve("config"));

            if (instance != null
                    && instance.getMinecraftVersion().equalsIgnoreCase(version.getMinecraftVersion().getVersion())
                    && instance.hasCustomMods()) {
                Utils.deleteWithFilter(this.root.resolve("mods").toFile(),
                        instance.getCustomMods(com.atlauncher.data.Type.mods));
                if (this.version.getMinecraftVersion().usesCoreMods()) {
                    Utils.deleteWithFilter(getCoreModsDirectory(),
                            instance.getCustomMods(com.atlauncher.data.Type.coremods));
                }
                if (isReinstall) {
                    Utils.deleteWithFilter(getJarModsDirectory(), instance.getCustomMods(com.atlauncher.data.Type.jar));
                }
            } else {
                FileUtils.deleteDirectory(this.root.resolve("mods"));
                if (this.version.getMinecraftVersion().usesCoreMods()) {
                    FileUtils.deleteDirectory(this.root.resolve("coremods"));
                }

                if (isReinstall) {
                    FileUtils.deleteDirectory(this.root.resolve("jarmods"));
                }
            }

            if (isReinstall) {
                FileUtils.delete(this.root.resolve("texturepacks/TexturePack.zip"));
                FileUtils.delete(this.root.resolve("resourcepacks/ResourcePack.zip"));
            } else {
                FileUtils.deleteDirectory(this.root.resolve("libraries"));
            }

            if (this.instance != null && this.packVersion.deletes != null) {
                Deletes deletes = this.packVersion.deletes;

                if (deletes.hasFileDeletes()) {
                    for (Delete delete : deletes.getFiles()) {
                        if (delete.isAllowed()) {
                            File file = delete.getFile(this.instance.getRootDirectory());
                            if (file.exists()) {
                                Utils.delete(file);
                            }
                        }
                    }
                }

                if (deletes.hasFolderDeletes()) {
                    for (Delete delete : deletes.getFolders()) {
                        if (delete.isAllowed()) {
                            File file = delete.getFile(this.instance.getRootDirectory());
                            if (file.exists()) {
                                Utils.delete(file);
                            }
                        }
                    }
                }
            }
        }

        // make some new directories
        Path[] directories;
        if (isServer) {
            directories = new Path[] { this.root, this.root.resolve("mods"), this.temp,
                    this.root.resolve("libraries") };
        } else {
            directories = new Path[] { this.root, this.root.resolve("mods"), this.root.resolve("disabledmods"),
                    this.temp, this.root.resolve("jarmods"), this.root.resolve("bin"),
                    this.root.resolve("bin/natives") };
        }

        for (Path directory : directories) {
            if (!Files.exists(directory)) {
                Files.createDirectory(directory);
            }
        }

        if (this.version.getMinecraftVersion().usesCoreMods()) {
            Files.createDirectory(this.root.resolve("coremods"));
        }
    }

    private void restoreSelectFiles() {
        if (savedReis) {
            Utils.copyDirectory(new File(getTempDirectory(), "rei_minimap"),
                    new File(getModsDirectory(), "rei_minimap"));
        }

        if (savedZans) {
            Utils.copyDirectory(new File(getTempDirectory(), "VoxelMods"), new File(getModsDirectory(), "VoxelMods"));
        }

        if (savedNEICfg) {
            Utils.copyFile(new File(getTempDirectory(), "NEI.cfg"), new File(getConfigDirectory(), "NEI.cfg"), true);
        }

        if (savedOptionsTxt) {
            Utils.copyFile(new File(getTempDirectory(), "options.txt"), new File(getRootDirectory(), "options.txt"),
                    true);
        }

        if (savedServersDat) {
            Utils.copyFile(new File(getTempDirectory(), "servers.dat"), new File(getRootDirectory(), "servers.dat"),
                    true);
        }

        if (savedPortalGunSounds) {
            Utils.copyFile(new File(getTempDirectory(), "PortalGunSounds.pak"),
                    new File(getModsDirectory(), "PortalGunSounds.pak"), true);
        }
    }

    private void installServerBootScripts() throws Exception {
        if (!isServer) {
            return;
        }

        File batFile = new File(getRootDirectory(), "LaunchServer.bat");
        File shFile = new File(getRootDirectory(), "LaunchServer.sh");
        Utils.replaceText(new File(App.settings.getLibrariesDir(), "LaunchServer.bat"), batFile, "%%SERVERJAR%%",
                getServerJar());
        Utils.replaceText(new File(App.settings.getLibrariesDir(), "LaunchServer.sh"), shFile, "%%SERVERJAR%%",
                getServerJar());
        batFile.setExecutable(true);
        shFile.setExecutable(true);
    }

    public String getServerJar() {
        if (this.loader != null) {
            return this.loader.getServerJar();
        }

        com.atlauncher.data.json.Mod forge = null;
        com.atlauncher.data.json.Mod mcpc = null;
        for (com.atlauncher.data.json.Mod mod : this.selectedMods) {
            if (mod.getType() == com.atlauncher.data.json.ModType.forge) {
                forge = mod;
            } else if (mod.getType() == com.atlauncher.data.json.ModType.mcpc) {
                mcpc = mod;
            }
        }
        if (mcpc != null) {
            return mcpc.getFile();
        } else if (forge != null) {
            return forge.getFile();
        } else {
            return "minecraft_server." + this.version.getMinecraftVersion().getVersion() + ".jar";
        }
    }

    protected void fireProgress(double percent) {
        if (percent > 100.0) {
            percent = 100.0;
        }
        firePropertyChange("progress", null, percent);
    }

    protected void fireSubProgress(double percent) {
        if (percent > 100.0) {
            percent = 100.0;
        }
        firePropertyChange("subprogress", null, percent);
    }

    protected void fireSubProgress(double percent, String paint) {
        if (percent > 100.0) {
            percent = 100.0;
        }
        String[] info = new String[2];
        info[0] = "" + percent;
        info[1] = paint;
        firePropertyChange("subprogress", null, info);
    }

    protected void addPercent(double percent) {
        this.percent = this.percent + percent;
        if (this.percent > 100.0) {
            this.percent = 100.0;
        }
        fireProgress(this.percent);
    }

    public void setSubPercent(double percent) {
        this.subPercent = percent;
        if (this.subPercent > 100.0) {
            this.subPercent = 100.0;
        }
        fireSubProgress(this.subPercent);
    }

    public void addSubPercent(double percent) {
        this.subPercent = this.subPercent + percent;
        if (this.subPercent > 100.0) {
            this.subPercent = 100.0;
        }

        if (this.subPercent > 100.0) {
            this.subPercent = 100.0;
        }
        fireSubProgress(this.subPercent);
    }

    public void setTotalBytes(long bytes) {
        this.downloadedBytes = 0L;
        this.totalBytes = bytes;
        this.updateProgressBar();
    }

    public void addDownloadedBytes(long bytes) {
        this.downloadedBytes += bytes;
        this.updateProgressBar();
    }

    private void updateProgressBar() {
        double progress;
        if (this.totalBytes > 0) {
            progress = (this.downloadedBytes / this.totalBytes) * 100.0;
        } else {
            progress = 0.0;
        }
        double done = this.downloadedBytes / 1024.0 / 1024.0;
        double toDo = this.totalBytes / 1024.0 / 1024.0;
        if (done > toDo) {
            fireSubProgress(100.0, String.format("%.2f MB", done));
        } else {
            fireSubProgress(progress, String.format("%.2f MB / %.2f MB", done, toDo));
        }
    }

    private void hideSubProgressBar() {
        fireSubProgress(-1);
    }
}
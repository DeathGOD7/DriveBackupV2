package ratismal.drivebackup.util;

import ratismal.drivebackup.UploadThread.UploadLogger;
import ratismal.drivebackup.config.ConfigParser;
import ratismal.drivebackup.config.ConfigParser.Config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static ratismal.drivebackup.config.Localization.intl;

/**
 * Created by Ratismal on 2016-01-20.
 */

public class FileUtil {
    private UploadLogger logger;

    public FileUtil(UploadLogger logger) {
        this.logger = logger;
    }

    /**
     * Gets the local backups in the specified folder as a {@code TreeMap} with their creation date and a reference to them
     * @param location the location of the folder containing the backups
     * @param formatter the format of the file name
     * @return The list of backups
     */
    public TreeMap<Long, File> getLocalBackups(String location, LocalDateTimeFormatter formatter) {
        location = escapeBackupLocation(location);

        TreeMap<Long, File> backupList = new TreeMap<>();
        String path = new File(ConfigParser.getConfig().backupStorage.localDirectory).getAbsolutePath() + "/" + location;
        File[] files = new File(path).listFiles();

        for (File file : files) {
            if (file.getName().endsWith(".zip")) {

                String fileName = file.getName();

                try {
                    ZonedDateTime date = formatter.parse(fileName);
                    backupList.put(date.toEpochSecond(), file);
                } catch (Exception e) {
                    // Fallback to using file creation date if the file name doesn't match the format
                    backupList.put((file.lastModified() / 1000), file);
                    logger.log(intl("local-backup-date-format-invalid"), "file-name", fileName);
                }
            }
        }

        return backupList;
    }

    /**
     * Creates a local backup zip file for the specified file/folder
     * @param location the location of the file or folder
     * @param formatter the format of the file name
     * @param blacklistGlobs a list of glob patterns of files/folders to not include in the backup
     * @throws Exception
     */
    public void makeBackup(String location, LocalDateTimeFormatter formatter, List<String> blacklistGlobs) throws Exception {
        Config config = ConfigParser.getConfig();

        if (location.charAt(0) == '/') {
            throw new IllegalArgumentException(); 
        }

        ZonedDateTime now = ZonedDateTime.now(config.advanced.dateTimezone);
        String fileName = formatter.format(now);

        String subfolderName = location;
        if (isBaseFolder(subfolderName)) {
            subfolderName = "root";
        }

        File path = new File(escapeBackupLocation(config.backupStorage.localDirectory + "/" + subfolderName));
        if (!path.exists()) {
            path.mkdirs();
        }

        List<BlacklistEntry> blacklist = new ArrayList<>();
        for (String blacklistGlob : blacklistGlobs) {
            BlacklistEntry blacklistEntry = new BlacklistEntry(
                blacklistGlob, 
                FileSystems.getDefault().getPathMatcher("glob:" + blacklistGlob)
                );

            blacklist.add(blacklistEntry);
        }

        BackupFileList fileList = generateFileList(location, blacklist);

        for (BlacklistEntry blacklistEntry : fileList.getBlacklist()) {
            String globPattern = blacklistEntry.getGlobPattern();
            int blacklistedFiles = blacklistEntry.getBlacklistedFiles();

            if (blacklistedFiles > 0) {
                logger.info(
                    intl("local-backup-backlisted"),
                    "blacklisted-files-count", String.valueOf(blacklistedFiles),
                    "glob-pattern", globPattern);
            }
        }

        int filesInBackupFolder = fileList.getFilesInBackupFolder();
        if (filesInBackupFolder > 0) {
            logger.info(
                intl("local-backup-in-backup-folder"), 
                "files-in-backup-folder-count", String.valueOf(filesInBackupFolder));
        }

        zipIt(location, path.getPath() + "/" + fileName, fileList);
    }

    /**
     * Deletes the oldest files in the specified folder past the number to retain locally
     * <p>
     * The number of files to retain locally is specified by the user in the {@code config.yml}
     * @param location the location of the folder containing the backups
     * @param formatter the format of the files name
     */
    public void pruneLocalBackups(String location, LocalDateTimeFormatter formatter) {
        int localKeepCount = ConfigParser.getConfig().backupStorage.localKeepCount;
        location = escapeBackupLocation(location);

        if (localKeepCount != -1) {
            try {
                TreeMap<Long, File> backupList = getLocalBackups(location, formatter);

                if (backupList.size() > localKeepCount) {
                    logger.info(
                        intl("local-backup-limit-reached"),
                        "backup-count", String.valueOf(backupList.size()),
                        "backup-limit", String.valueOf(localKeepCount));
                }
                

                while (backupList.size() > localKeepCount) {
                    File fileToDelete = backupList.descendingMap().lastEntry().getValue();
                    long dateOfFile = backupList.descendingMap().lastKey();

                    if (!fileToDelete.delete()) {
                        logger.log(
                            intl("local-backup-file-failed-to-delete"),
                            "local-backup-name", fileToDelete.getName());
                    }
                    
                    backupList.remove(dateOfFile);
                }
            } catch (Exception e) {
                logger.log(intl("local-backup-failed-to-delete"));
                MessageUtil.sendConsoleException(e);
            }
        }
    }

    /**
     * Zips files in the specified folder into the specified file location
     * @param inputFolderPath the path of the zip file to create
     * @param outputFilePath the path of the folder to put it in
     * @param fileList
     */
    private void zipIt(String inputFolderPath, String outputFilePath, BackupFileList fileList) throws Exception {
        byte[] buffer = new byte[1024];
        FileOutputStream fileOutputStream;
        ZipOutputStream zipOutputStream = null;

        String formattedInputFolderPath = new File(inputFolderPath).getName();
        if (isBaseFolder(inputFolderPath)) {
            formattedInputFolderPath = "root";
        }

        try {
            fileOutputStream = new FileOutputStream(outputFilePath);
            zipOutputStream = new ZipOutputStream(fileOutputStream);
            zipOutputStream.setLevel(ConfigParser.getConfig().backupStorage.zipCompression);

            for (String file : fileList.getList()) {
                zipOutputStream.putNextEntry(new ZipEntry(formattedInputFolderPath + "/" + file));

                try (FileInputStream fileInputStream = new FileInputStream(inputFolderPath + "/" + file)) {
                    
                    int len;
                    while ((len = fileInputStream.read(buffer)) > 0) {
                        zipOutputStream.write(buffer, 0, len);
                    }
                } catch (Exception e) {
                    String filePath = new File(inputFolderPath, file).getPath();

                    if (!filePath.endsWith(".lock")) { // Don't send warning for .lock files, they will always be locked
                        logger.info(
                            intl("local-backup-failed-to-include"),
                            "file-path", filePath);
                    }
                }

                zipOutputStream.closeEntry();
            }
            
            zipOutputStream.close();
        } catch (Exception exception) {
            if (zipOutputStream != null) {
                zipOutputStream.close();
            }

            throw exception; 
        }
    }

    /**
     * A list of files to put in a zip file
     * Mutable
     */
    private static class BackupFileList {
        int filesInBackupFolder;
        List<String> fileList;
        List<BlacklistEntry> blacklist;

        BackupFileList(List<BlacklistEntry> blacklist) {
            this.filesInBackupFolder = 0;
            this.fileList = new ArrayList<>();
            this.blacklist = blacklist;
        }

        void incFilesInBackupFolder() {
            filesInBackupFolder++;
        }

        public int getFilesInBackupFolder() {
            return filesInBackupFolder;
        }

        void appendToList(String file) {
            fileList.add(file);
        }

        List<String> getList() {
            return fileList;
        }

        List<BlacklistEntry> getBlacklist() {
            return blacklist;
        }
    }

    /**
     * Generates a list of files to put in the zip created from the specified folder
     * @param inputFolderPath The path of the folder to create the zip from
     * @throws Exception
     */
    private BackupFileList generateFileList(String inputFolderPath, List<BlacklistEntry> blacklist) throws Exception {
        BackupFileList fileList = new BackupFileList(blacklist);

        generateFileList(new File(inputFolderPath), inputFolderPath, fileList);

        return fileList;
    }

    /**
     * Adds the specified file or folder to the list of files to put in the zip created from the specified folder
     * @param file the file or folder to add
     * @param inputFolderPath the path of the folder to create the zip 
     * @param fileList the list of files to add the specified file or folder to
     * @throws Exception
     */
    private void generateFileList(File file, String inputFolderPath, BackupFileList fileList) throws Exception {

        if (file.isFile()) {
            // Verify not backing up previous backups
            if (file.getCanonicalPath().startsWith(new File(ConfigParser.getConfig().backupStorage.localDirectory).getCanonicalPath())) {
                fileList.incFilesInBackupFolder();;

                return;
            }

            Path relativePath = Paths.get(inputFolderPath).relativize(file.toPath());

            for (BlacklistEntry blacklistEntry : fileList.getBlacklist()) {
                if (blacklistEntry.getPathMatcher().matches(relativePath)) {
                    blacklistEntry.incBlacklistedFiles();

                    return;
                }
            }

            fileList.appendToList(relativePath.toString());
        } else {
            for (String filename : file.list()) {
                generateFileList(new File(file, filename), inputFolderPath, fileList);
            }
        }
    }

    /**
     * Removes ".." from the location string in order to keep the location's backup folder within the local-save-directory
     * @param location the unescaped location
     * @return the escaped location
     */
    private static String escapeBackupLocation(String location) {
        return location.replace("../", "");
    }

    /**
     * Finds all folders that match a glob
     * @param glob the glob to search
     * @param rootPath the path to start searching from
     * @return List of all folders that match this glob under rootPath
     */
    public static List<Path> generateGlobFolderList(String glob, String rootPath) {
        PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:./" + glob);
        List<Path> list = new ArrayList<Path>();
        try (Stream<Path> walk = Files.walk(Paths.get(rootPath))) {
            list = walk.filter(pathMatcher::matches).filter(Files::isDirectory).collect(Collectors.toList());
        } catch (IOException exception) {
            return list;
        }
        return list;
    }

    /**
     * Whether the specified folder is the base folder of the Minecraft server
     * <p>
     * In other words, whether the folder is the folder containing the server jar
     * @param folderPath the path of the folder
     * @return whether the folder is the base folder
     * @throws Exception
     */
    public static boolean isBaseFolder(String folderPath) throws Exception {
        return new File(folderPath).getPath().equals(".");
    }

    /**
     * Deletes the specified folder
     * @param folder the folder to be deleted
     * @return whether deleting the folder was successful
     */
    public static boolean deleteFolder(File folder) {
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                deleteFolder(file);
            }
        }
        return folder.delete();
    }
}

package se.artcomputer.ftp;

import org.apache.commons.net.ftp.FTPFile;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

public class ArchiveFiles {

    private static final String ARCHIVE = "/Volume_1/camera/archive";
    private static final String CAMERA = "/Volume_1/camera/camera";
    private static FtpClient ftpClient;

    public static void main(String[] args) throws IOException, InterruptedException {

        String user = "perty";
        String password = "Six10Son1a";
        String server = "192.168.86.207";
        int port = 21;

        ftpClient = new FtpClient(user, password, server, port);
        ftpClient.open();
        while (true) {
            ftpClient.cd(CAMERA);
            List<FTPFile> ftpFiles = ftpClient.listFiles();
            if (ftpFiles.size() >= 2000) {
                String path = archiveFiles(ftpFiles);
                System.out.println("Archived 2000 files.\n" + path + "\n");
                Thread.sleep(1000);
            } else {
                break;
            }
        }

        ftpClient.close();
    }

    private static String archiveFiles(List<FTPFile> ftpFiles) throws IOException {
        String archivePath = makeArchive();
        for (FTPFile file : ftpFiles) {
            ftpClient.moveFile(CAMERA, file.getName(), ARCHIVE + "/" + archivePath);
        }
        return archivePath;
    }

    private static String makeArchive() throws IOException {
        ftpClient.cd(ARCHIVE);
        List<String> archiveDirs = ftpClient.listDirectories()
                .stream()
                .map(FTPFile::getName)
                .sorted()
                .collect(Collectors.toList());
        String result = archiveDirs.isEmpty() ? "a1" : getNext(archiveDirs);
        ftpClient.makeDirectory(result);
        return result;
    }

    private static String getNext(List<String> archiveDirs) {
        return String.format("a%06d", Integer.parseInt(archiveDirs.get(archiveDirs.size() - 1).substring(1)) + 1);
    }

}


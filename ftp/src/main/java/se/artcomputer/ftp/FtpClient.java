package se.artcomputer.ftp;

import org.apache.commons.net.PrintCommandListener;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;

class FtpClient {

    private final String server;
    private final int port;
    private final String user;
    private final String password;
    private FTPClient ftp;

    FtpClient(String user, String password, String server, int port) {
        this.user = user;
        this.password = password;
        this.server = server;
        this.port = port;
    }

    // constructor

    void open() throws IOException {
        ftp = new FTPClient();

        ftp.addProtocolCommandListener(new PrintCommandListener(new PrintWriter(System.out)));

        ftp.connect(server, port);
        int reply = ftp.getReplyCode();
        if (!FTPReply.isPositiveCompletion(reply)) {
            ftp.disconnect();
            throw new IOException("Exception in connecting to FTP Server");
        }

        ftp.login(user, password);
    }

    public List<FTPFile> listFiles() throws IOException {
        return Arrays.asList(ftp.listFiles());
    }

    public List<FTPFile> listDirectories() throws IOException {
        return Arrays.asList(ftp.listDirectories());
    }

    public void cd(String path) throws IOException {
        ftp.changeWorkingDirectory(path);
    }

    void close() throws IOException {
        ftp.disconnect();
    }

    public void makeDirectory(String name) throws IOException {
        ftp.makeDirectory(name);
    }

    public void moveFile(String fromDir, String fileName, String toDir) throws IOException {
        String from = fromDir + "/" + fileName;
        String to = toDir + "/" + fileName;
        ftp.rename(from, to);
    }
}
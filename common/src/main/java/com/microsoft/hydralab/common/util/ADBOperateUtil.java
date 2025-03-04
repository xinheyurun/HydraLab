// Copyright (c) Microsoft Corporation.
// Licensed under the MIT License.
package com.microsoft.hydralab.common.util;

import com.android.ddmlib.*;
import com.microsoft.hydralab.common.entity.common.DeviceInfo;
import com.microsoft.hydralab.common.logger.MultiLineNoCancelLoggingReceiver;
import com.microsoft.hydralab.common.logger.MultiLineNoCancelReceiver;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Service
public class ADBOperateUtil {
    private static final int ADB_WAIT_TIMEOUT_SECONDS = 120;
    private final Logger instanceLogger = LoggerFactory.getLogger(ADBOperateUtil.class);
    Runtime runtime = Runtime.getRuntime();
    private String mAndroidHome;
    private File mAdbPath;
    private String adbServerHost = DdmPreferences.DEFAULT_ADBHOST_VALUE;
    private AndroidDebugBridge mAndroidDebugBridge;
    public void init(AndroidDebugBridge.IDeviceChangeListener mListener) throws IOException {
        mAndroidHome = System.getenv("ANDROID_HOME");
        Assert.notNull(mAndroidHome, "ANDROID_HOME env var must be set and pointing to the home path of Android SDK.");

        AndroidDebugBridge.initIfNeeded(false);
        if (!Objects.equals(adbServerHost, DdmPreferences.DEFAULT_ADBHOST_VALUE)) {
            changeADBSocketHostAddr(adbServerHost);
            instanceLogger.info("ADB server hostname is changed to {}", adbServerHost);
        }
        AndroidDebugBridge.addDeviceChangeListener(mListener);

        boolean onWindows = MachineInfoUtils.isOnWindows();
        if (onWindows) {
            if (LogUtils.isLegalStr(mAndroidHome, Const.RegexString.WINDOWS_PATH, false)) {
                mAdbPath = new File(mAndroidHome, "platform-tools" + File.separator + "adb.exe");
            }
        } else {
            if (LogUtils.isLegalStr(mAndroidHome, Const.RegexString.LINUX_PATH, false)) {
                mAdbPath = new File(mAndroidHome, "platform-tools" + File.separator + "adb");
            }
        }
        Assert.isTrue(mAdbPath.exists(), "ADB must be available in " + mAdbPath.getAbsolutePath());

        // com.android.ddmlib.AndroidDebugBridge.createBridge(java.lang.String, boolean) is a deprecated API
        // com.android.ddmlib.AndroidDebugBridge#createBridge(java.lang.String, boolean, long, java.util.concurrent.TimeUnit)
        // is recommended as it requires a timeout param to indicate an ADB server connection issue instead of forever hanging.
        mAndroidDebugBridge = AndroidDebugBridge.createBridge(mAdbPath.getCanonicalPath(), true);
        Assert.notNull(mAndroidDebugBridge, "Create AndroidDebugBridge failed");
    }

    /**
     * This method will use reflection API to modify the value of the ADB server host address.
     * For more details of the source code, you may refer to
     * <a href="https://android.googlesource.com/platform/tools/base/+/refs/tags/platform-tools-33.0.3/ddmlib/src/main/java/com/android/ddmlib/AndroidDebugBridge.java">AndroidDebugBridge.java</a> in Google AOSP repos.
     *
     * @param adbServerHost adb server hostname, we need to change this to make it work inside a docker container. E.g. host.docker.internal or vm.docker.internal.
     */
    private void changeADBSocketHostAddr(String adbServerHost) {
        int port = AndroidDebugBridge.getSocketAddress().getPort();
        try {
            Field sSocketAddrField = AndroidDebugBridge.class.getDeclaredField("sSocketAddr");
            sSocketAddrField.setAccessible(true);
            sSocketAddrField.set(null, new InetSocketAddress(InetAddress.getByName(adbServerHost), port));
        } catch (NoSuchFieldException | UnknownHostException | IllegalAccessException e) {
            instanceLogger.error("Error when changing the value of AndroidDebugBridge sSocketAddr", e);
        }
    }

    public RawImage getScreenshot(DeviceInfo deviceInfo, Logger logger) throws Exception {
        IDevice device = getDeviceByInfo(deviceInfo);
        if (device == null) {
            return null;
        }
        getNotNullLogger(logger).info("getScreenshot");
        return device.getScreenshot();
    }

    private IDevice getDeviceByInfo(DeviceInfo deviceInfo) {
        for (IDevice device : mAndroidDebugBridge.getDevices()) {
            if (device.isOnline()) {
                if (device.getSerialNumber().equals(deviceInfo.getSerialNum())) {
                    return device;
                }
            }
        }
        return null;
    }

    public void setAdbServerHost(String adbServerHost) {
        this.adbServerHost = adbServerHost;
    }

    public void execOnDevice(DeviceInfo deviceInfo, String comm, IShellOutputReceiver receiver, @Nullable Logger logger) {
        Logger localLogger = getNotNullLogger(logger);
        IDevice device = getDeviceByInfo(deviceInfo);
        if (device == null) {
            throw new RuntimeException("No such device: " + deviceInfo);
        }
        localLogger.info(">> adb -s {} shell {}", device.getSerialNumber(), comm);
        try {
            device.executeShellCommand(comm, receiver);
            deviceInfo.setAdbTimeout(false);
        } catch (TimeoutException te) {
            deviceInfo.setAdbTimeout(true);
            // todo: add email alert for TimeoutException
            localLogger.error("TimeoutException in execOnDevice: " + te.getMessage(), te);
        } catch (ShellCommandUnresponsiveException scue) {
            // todo: add email alert for ShellCommandUnresponsiveException. When this or TimeoutException happens, maybe we can put an unstable tag to this connection directly.
            //       Measures we can take: fire an alert / reboot the device / restart the adb server.
            //     We should design a simple state machine or signal handler in the device object to handle the signals and react with
            //     corresponding approaches.
            localLogger.error("ShellCommandUnresponsiveException in execOnDevice: " + scue.getMessage(), scue);
        } catch (AdbCommandRejectedException acre) {
            // todo: add email alert for AdbCommandRejectedException
            localLogger.error("AdbCommandRejectedException in execOnDevice: " + acre.getMessage(), acre);
        } catch (IOException e) {
            localLogger.error("IOException in execOnDevice: " + e.getMessage(), e);
        }
    }

    public void executeShellCommandOnDevice(DeviceInfo deviceInfo, String command, IShellOutputReceiver receiver, int testTimeOutSec) throws ShellCommandUnresponsiveException, AdbCommandRejectedException, IOException, TimeoutException {
        IDevice device = getDeviceByInfo(deviceInfo);
        Assert.notNull(device, "Not such device is available " + deviceInfo.getSerialNum());
        device.executeShellCommand(command, receiver, testTimeOutSec, 120, TimeUnit.SECONDS);
    }

    public Process executeDeviceCommandOnPC(DeviceInfo deviceInfo, String command, Logger logger) throws IOException {
        String commandLine = String.format("%s -H %s -s %s %s", mAdbPath.getAbsolutePath(), adbServerHost, deviceInfo.getSerialNum(), command);
        getNotNullLogger(logger).info("executeDeviceCommandOnPC: {}", commandLine);
        return runtime.exec(commandLine);
    }


    public Process executeCommandOnPC(String command, Logger logger) throws IOException {
        String commandLine = String.format("%s -H %s %s", mAdbPath.getAbsolutePath(), adbServerHost, command);
        getNotNullLogger(logger).info(commandLine);
        return runtime.exec(commandLine);
    }

    public void clickOnDeviceAbsoluteXY(DeviceInfo deviceInfo, int xPos, int yPos, @Nullable Logger logger) {
        String command = String.format("input tap %d %d", xPos, yPos);
        getNotNullLogger(logger).info("> {} on {}", command, deviceInfo.getSerialNum());
        execOnDevice(deviceInfo, command, new MultiLineNoCancelLoggingReceiver(logger), logger);

    }

    public Logger getNotNullLogger(Logger logger) {
        if (logger != null) {
            return logger;
        }
        return instanceLogger;
    }

    public boolean installApp(DeviceInfo deviceInfo, String packagePath, boolean reinstall, String extArgs, Logger logger) throws InstallException {
        IDevice deviceByInfo = getDeviceByInfo(deviceInfo);
        Assert.notNull(deviceByInfo, "No such device: " + deviceInfo);
        getNotNullLogger(logger).info("adb -H {} -s {} shell pm install {} {} {}", adbServerHost, deviceInfo.getSerialNum(),
                extArgs, reinstall ? "-r" : "", packagePath);
        InstallReceiver receiver = new InstallReceiver();
        deviceByInfo.installPackage(packagePath, reinstall, receiver, extArgs);
        if (receiver.getErrorMessage() != null) {
            getNotNullLogger(logger).error("installApp Error code: {}, Error msg: {}", receiver.getErrorCode(), receiver.getErrorMessage());
        }
        if (receiver.getSuccessMessage() != null) {
            getNotNullLogger(logger).info("Install app success: {}", receiver.getSuccessMessage());
        }
        return receiver.isSuccessfullyCompleted();
    }


    public boolean uninstallApp(DeviceInfo deviceInfo, String packageName, Logger logger) throws InstallException {
        IDevice deviceByInfo = getDeviceByInfo(deviceInfo);
        Assert.notNull(deviceByInfo, "No such device: " + deviceInfo);
        String msg = deviceByInfo.uninstallPackage(packageName);
        getNotNullLogger(logger).info("adb -H {} -s {} shell pm uninstall {}", adbServerHost, deviceInfo.getSerialNum(), packageName);
        if (msg != null) {
            getNotNullLogger(logger).error("uninstall error, msg: {}", msg);
            return false;
        }
        return true;
    }

    public void pullFileToDir(DeviceInfo deviceInfo, String filePath, String pathOnDevice, Logger logger) {
        IDevice deviceByInfo = getDeviceByInfo(deviceInfo);
        if (deviceByInfo == null) {
            throw new RuntimeException("No such device: " + deviceInfo);
        }
        try {
            String comm = String.format("pull %s %s", pathOnDevice, filePath);
            Process process = executeDeviceCommandOnPC(deviceInfo, comm, logger);
            CommandOutputReceiver err = new CommandOutputReceiver(process.getErrorStream(), logger);
            CommandOutputReceiver out = new CommandOutputReceiver(process.getInputStream(), logger);
            err.start();
            out.start();
            process.waitFor(60, TimeUnit.SECONDS);
            //deviceByInfo.pullFile(pathOnDevice, folder.getAbsolutePath());
        } catch (Exception e) {
            getNotNullLogger(logger).error(e.getMessage(), e);
        }
    }

    public long getFileLength(DeviceInfo deviceInfo, Logger logger, String filePath) {

        try {
            final Long[] length = new Long[1];
            execOnDevice(deviceInfo, String.format("wc -c %s", filePath), new MultiLineNoCancelReceiver() {
                @Override
                public void processNewLines(@NotNull String[] lines) {
                    try {
                        String[] result = lines[0].split("\\s+");
                        if (result[0].trim().length() > 0) {
                            length[0] = Long.parseLong(result[0].trim());
                        }
                    } catch (Exception e) {
                        throw new RuntimeException("error when parsing file size: " + lines[0], e);
                    }
                }
            }, getNotNullLogger(logger));
            return length[0];
        } catch (Exception e) {
            getNotNullLogger(logger).error(e.getMessage(), e);
            return 0;
        }
    }

    public int getPackagePid(DeviceInfo deviceInfo, String pkgName, Logger logger) {
        final int[] pid = {0};
        execOnDevice(deviceInfo, "ps", new MultiLineNoCancelReceiver() {
            @Override
            public void processNewLines(@NotNull String[] lines) {
                for (String line : lines) {
                    if (StringUtils.isBlank(line)) {
                        continue;
                    }
                    if (line.contains("com.")) {
                        getNotNullLogger(logger).info(line);
                    }
                    if (line.contains(pkgName) && !line.contains(pkgName + ":")) {
                        String[] args = line.split("\\s+");
                        for (String arg : args) {
                            arg = arg.trim();
                            if (arg.matches("\\d+")) {
                                pid[0] = Integer.parseInt(arg);
                                return;
                            }
                        }
                    }
                }
            }
        }, getNotNullLogger(logger));
        return pid[0];
    }
}

urls({});

export default {
  executable: context.os === "windows" ? "powershell.exe" : `${context.packageDir}/demo.sh`,
  arguments: context.os === "windows"
    ? ["-NoProfile", "-File", `${context.packageDir}/demo.ps1`, ...context.args]
    : context.args,
};

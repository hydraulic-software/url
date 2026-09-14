let args = context.args;
if (context.os === "windows")
  args = ["-NoProfile", "-File", `${context.packageDir}/demo.ps1`, ...context.args]

export default {
  executable: context.os === "windows" ? "powershell.exe" : `${context.packageDir}/demo.sh`,
  arguments: args
};

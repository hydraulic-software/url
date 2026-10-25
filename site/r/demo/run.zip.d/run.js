import { isWindows } from "./utils.js";

let args = context.args;
if (isWindows)
  args = ["-NoProfile", "-File", `${context.packageDir}/demo.ps1`, ...context.args]

export default {
  executable: isWindows ? "powershell.exe" : `${context.packageDir}/demo.sh`,
  arguments: args
};

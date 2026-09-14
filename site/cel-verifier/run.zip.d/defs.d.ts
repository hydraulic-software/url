type RunOs = "linux" | "macos" | "android" | "freebsd" | "windows";
type RunArch = "x86_64" | "arm64";

declare const context: Readonly<{
    os: RunOs;
    arch: RunArch;
    ver: string | null;
    args: readonly string[];
    packageDir: string;
}>;

declare function urls<T extends Record<string, string>>(requests: T): Readonly<{ [K in keyof T]: string; }>;

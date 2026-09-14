type RunOs = "linux" | "macos" | "android" | "freebsd" | "windows";
type RunArch = "x86_64" | "arm64";

declare const context: Readonly<{
    os: RunOs;
    arch: RunArch;
    ver: string | null;
    args: readonly string[];
    packageDir: string;
}>;

type UrlInput = string | readonly string[];
type UrlOutput<T> = T extends readonly string[] ? readonly string[] : string;

declare function urls(requests: readonly string[]): readonly string[];
declare function urls<T extends Record<string, UrlInput>>(
    requests: T
): Readonly<{ [K in keyof T]: UrlOutput<T[K]> }>;

/**
 * Small ECMAScript-module parser following the useful subset of Node's
 * util.parseArgs API. It takes args explicitly and has no Node dependencies.
 */
export function parseArgs(args, { options = {}, strict = true, allowPositionals = !strict } = {}) {
  const values = Object.create(null);
  const positionals = [];
  const shortOptions = new Map();

  for (const [name, option] of Object.entries(options)) {
    if (option.type !== "string" && option.type !== "boolean") {
      throw new TypeError(`Option --${name} must have type "string" or "boolean"`);
    }
    if (option.short) shortOptions.set(option.short, [name, option]);
  }

  function store(name, option, value) {
    if (!option) {
      if (strict) throw new Error(`Unknown option: --${name}`);
      values[name] = value ?? true;
      return;
    }
    if (option.type === "boolean" && value !== undefined) {
      if (strict) throw new Error(`Option --${name} does not take a value`);
    } else if (option.type === "string" && value === undefined && strict) {
      throw new Error(`Option --${name} requires a value`);
    }

    const nextValue = option.type === "boolean" ? true : value ?? true;
    if (option.multiple) {
      (values[name] ??= []).push(nextValue);
    } else {
      values[name] = nextValue;
    }
  }

  function parseShort(token, next) {
    const group = token.slice(1);
    for (let index = 0; index < group.length; index++) {
      const short = group[index];
      const [name, option] = shortOptions.get(short) ?? [short, undefined];
      if (!option || option.type === "boolean") {
        store(name, option, undefined);
        continue;
      }

      const remainder = group.slice(index + 1);
      const inlineValue = remainder.startsWith("=") ? remainder.slice(1) : remainder || undefined;
      const value = inlineValue ?? (next && (next === "-" || !next.startsWith("-")) ? next : undefined);
      store(name, option, value);
      return inlineValue === undefined && value !== undefined ? 1 : 0;
    }
    return 0;
  }

  for (let index = 0; index < args.length; index++) {
    const argument = args[index];
    if (argument === "--") {
      positionals.push(...args.slice(index + 1));
      break;
    }
    if (!argument.startsWith("-") || argument === "-") {
      if (!allowPositionals) throw new Error(`Unexpected positional argument: ${argument}`);
      positionals.push(argument);
      continue;
    }
    if (argument.startsWith("--")) {
      const equals = argument.indexOf("=");
      const name = argument.slice(2, equals < 0 ? undefined : equals);
      const option = options[name];
      const inlineValue = equals < 0 ? undefined : argument.slice(equals + 1);
      const next = args[index + 1];
      const value = inlineValue ?? (option?.type === "string" && next && (next === "-" || !next.startsWith("-"))
        ? args[++index]
        : undefined);
      store(name, option, value);
      continue;
    }
    index += parseShort(argument, args[index + 1]);
  }

  return { values, positionals };
}

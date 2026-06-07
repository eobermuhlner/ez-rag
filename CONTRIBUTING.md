# Contributing

### Technology stack

- Kotlin 2.0, JDK 21
- Spring Boot 3.4 (no web server; `web-application-type: none`)
- Spring AI 1.0 (OpenAI, Anthropic, Ollama, Transformers starters)
- picocli 4.7 for argument parsing
- Gradle with Kotlin DSL

### Build

```sh
./gradlew build          # compile, test, assemble fat JAR
./gradlew test           # run tests only
./gradlew installDist    # produce runnable distribution under build/install/
```

The Kotlin compiler is configured with `-Werror`, so warnings are treated as build failures.

### Testing

The project uses test-driven development. Unit tests live in `src/test/kotlin/` alongside the source tree. Run them with:

```sh
./gradlew test
```

### Provider selection design

Spring AI's auto-configuration is fully disabled in `application.yml`. Provider beans are constructed manually in `ProviderConfiguration` based on the resolved config at startup. This avoids the need for Spring profiles and allows runtime provider selection from CLI flags without recompilation. To add a new provider, add a branch to `ProviderConfiguration.chatModel()` or `ProviderConfiguration.embeddingModel()`.

### Adding a new subcommand

1. Create a class in `command/` annotated with `@Command` and `@Component` that implements `Callable<Int>`.
2. Register it in the `subcommands` list of `@Command` on `EzRagCommand`.
3. Inject `ChatModel`, `EmbeddingModel`, or `ConfigService` as constructor parameters.

/**
 * Copyright (C) 2012 Spotify AB
 */

package com.spotify.helios.cli;

import com.spotify.helios.common.LoggingConfig;

import net.sourceforge.argparse4j.inf.ArgumentParserException;

import org.slf4j.LoggerFactory;

import java.io.PrintStream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

import static ch.qos.logback.classic.Level.ALL;
import static ch.qos.logback.classic.Level.DEBUG;
import static ch.qos.logback.classic.Level.INFO;
import static ch.qos.logback.classic.Level.WARN;
import static com.google.common.collect.Iterables.get;
import static java.util.Arrays.asList;
import static org.slf4j.Logger.ROOT_LOGGER_NAME;

/**
 * Instantiates and runs helios CLI.
 */
public class CliMain {

  private final CliParser parser;
  private final PrintStream out;
  private final PrintStream err;

  @SuppressWarnings("UseOfSystemOutOrSystemErr")
  public static void main(final String... args) {
    try {
      int exitCode = new CliMain(System.out, System.err, args).run();
      System.exit(exitCode);
    } catch (Throwable e) {
      // don't print error message for arg parser exception, because parser will do that
      if (!(e instanceof ArgumentParserException)) {
        System.err.println(e.getMessage());
      }
      System.exit(1);
    }
  }

  public CliMain(final PrintStream out, final PrintStream err, final String... args)
      throws Exception {
    this.parser = new CliParser(args);
    this.out = out;
    this.err = err;
    setupLogging();
  }

  public int run() {
    try {
      return parser.getCommand().run(parser.getNamespace(), parser.getTargets(), out, err,
                                     parser.getUsername(), parser.getJson());
    } catch (Exception e) {
      // print entire stack trace in verbose mode, otherwise just the exception message
      if (parser.getNamespace().getInt("verbose") > 0) {
        e.printStackTrace(err);
      } else {
        err.println(e.getMessage());
      }
      return 1;
    }
  }

  private void setupLogging() {
    final LoggingConfig config = parser.getLoggingConfig();
    if (config.getNoLogSetup()) {
      return;
    }
    final int verbose = config.getVerbosity();
    final Level level = get(asList(WARN, INFO, DEBUG, ALL), verbose, ALL);
    final Logger rootLogger = (Logger) LoggerFactory.getLogger(ROOT_LOGGER_NAME);
    rootLogger.setLevel(level);
  }
}
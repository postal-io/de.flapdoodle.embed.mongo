/**
 * Copyright (C) 2011
 *   Michael Mosmann <michael@mosmann.de>
 *   Martin Jöhren <m.joehren@googlemail.com>
 *
 * with contributions from
 * 	konstantin-ba@github,Archimedes Trajano	(trajano@github)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.flapdoodle.embed.mongo.runtime;

import static java.lang.String.format;
import static java.util.Arrays.asList;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.flapdoodle.embed.mongo.Command;
import de.flapdoodle.embed.mongo.config.MongoCmdOptions;
import de.flapdoodle.embed.mongo.config.MongodConfig;
import de.flapdoodle.embed.mongo.config.Storage;
import de.flapdoodle.embed.mongo.config.SupportConfig;
import de.flapdoodle.embed.mongo.distribution.Feature;
import de.flapdoodle.embed.process.distribution.Distribution;
import de.flapdoodle.embed.process.extract.ExtractedFileSet;
import de.flapdoodle.embed.process.io.file.Files;
import de.flapdoodle.embed.process.runtime.NUMA;

/**
 *
 */
public class Mongod extends AbstractMongo {

	private static Logger logger = LoggerFactory.getLogger(Mongod.class);

	/**
	 * Binary sample of shutdown command
	 */
	static final byte[] SHUTDOWN_COMMAND = { 0x58, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
			(byte) 0xD4, 0x07, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x61, 0x64, 0x6D, 0x69, 0x6E, 0x2E, 0x24, 0x63, 0x6D,
			0x64, 0x00, 0x00, 0x00, 0x00, 0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x2C, 0x00, 0x00,
			0x00, 0x10, 0x73, 0x68, 0x75, 0x74, 0x64, 0x6F, 0x77, 0x6E, 0x00, 0x01, 0x00, 0x00, 0x00, 0x08, 0x66, 0x6F,
			0x72, 0x63, 0x65, 0x00, 0x01, 0x10, 0x74, 0x69, 0x6D, 0x65, 0x6F, 0x75, 0x74, 0x53, 0x65, 0x63, 0x73, 0x00,
			0x00, 0x00, 0x00, 0x00, 0x00, 0x05, 0x00, 0x00, 0x00, 0x00 };
	private static final int SOCKET_TIMEOUT = 2000;
	private static final int CONNECT_TIMEOUT = 2000;
	private static final int BYTE_BUFFER_LENGTH = 512;
	private static final int WAITING_TIME_SHUTDOWN_IN_MS = 100;

	public static boolean sendShutdown(InetAddress hostname, int port) {
		if (!hostname.isLoopbackAddress()) {
			logger.warn("---------------------------------------\n"
					+ "Your localhost ({}) is not a loopback adress\n"
					+ "We can NOT send shutdown to mongod, because it is denied from remote.\n"
					+ "---------------------------------------\n", hostname.getHostAddress());
			return false;
		}

		boolean tryToReadErrorResponse = false;

		final Socket s = new Socket();
		try {
			s.setSoTimeout(SOCKET_TIMEOUT);
			s.connect(new InetSocketAddress(hostname, port), CONNECT_TIMEOUT);
			OutputStream outputStream = s.getOutputStream();
			outputStream.write(SHUTDOWN_COMMAND);
			outputStream.flush();

			tryToReadErrorResponse = true;
			InputStream inputStream = s.getInputStream();
			if (inputStream.read(new byte[BYTE_BUFFER_LENGTH]) != -1) {
				logger.error("Got some response, should be an error message");
				return false;
			}
			return true;
		} catch (IOException iox) {
			if (tryToReadErrorResponse) {
				return true;
			}
			logger.warn("sendShutdown {}:{}", hostname, port, iox);
		} finally {
			try {
				s.close();
				Thread.sleep(WAITING_TIME_SHUTDOWN_IN_MS);
			} catch (InterruptedException | IOException ix) {
				logger.warn("sendShutdown closing {}:{}", hostname, port, ix);
			}
		}
		return false;
	}

	public static int getMongodProcessId(String output, int defaultValue) {
		Pattern pattern = Pattern.compile("MongoDB starting : pid=([1234567890]+) port", Pattern.MULTILINE);
		Matcher matcher = pattern.matcher(output);
		if (matcher.find()) {
			String value = matcher.group(1);
			return Integer.valueOf(value);
		}
		return defaultValue;
	}

	public static List<String> getCommandLine(MongodConfig config, ExtractedFileSet files, File dbDir)
			throws UnknownHostException {
		List<String> ret = new ArrayList<>();
		ret.addAll(asList(Files.fileOf(files.baseDir(), files.executable()).getAbsolutePath(),
			"--dbpath", "" + dbDir.getAbsolutePath()));

		if (config.params() != null && !config.params().isEmpty()) {
			for (Object key : config.params().keySet()) {
				ret.addAll(asList(format("--setParameter"), format("%s=%s", key, config.params().get(key))));
			}
		}
		if (config.args() != null && !config.args().isEmpty()) {
			for (String key : config.args().keySet()) {
				ret.add(key);
				String val = config.args().get(key);
				if (val != null && !val.isEmpty()) {
					ret.add(val);
				}
			}
		}
		if (config.cmdOptions().auth()) {
			ret.add("--auth");
		} else {
			ret.add("--noauth");
		}
		if (config.cmdOptions().useNoPrealloc()) {
			ret.add("--noprealloc");
		}
		if (config.cmdOptions().useSmallFiles()) {
			ret.add("--smallfiles");
		}
		if (config.cmdOptions().useNoJournal() && !config.isConfigServer()) {
			ret.add("--nojournal");
		}
		if (config.cmdOptions().master()) {
			ret.add("--master");
		}

		if (config.version().enabled(Feature.STORAGE_ENGINE)) { 
			if (config.cmdOptions().storageEngine().isPresent()) {
				ret.add("--storageEngine");
				ret.add(config.cmdOptions().storageEngine().get());
			}
		}

		if (config.cmdOptions().isVerbose()) {
			ret.add("-v");
		}

		applyDefaultOptions(config, ret);
		applyNet(config, ret);

		Storage replication = config.replication();
		
		if (replication.getReplSetName() != null) {
			ret.add("--replSet");
			ret.add(replication.getReplSetName());
		}
		if (replication.getOplogSize() != 0) {
			ret.add("--oplogSize");
			ret.add(String.valueOf(replication.getOplogSize()));
		}
		if (config.isConfigServer()) {
			ret.add("--configsvr");
		}
		if (config.isShardServer()) {
			ret.add("--shardsvr");
		}
		if (config.version().enabled(Feature.SYNC_DELAY)) {
			applySyncDelay(ret, config.cmdOptions());
		}
		if (config.version().enabled(Feature.TEXT_SEARCH)) {
			applyTextSearch(ret, config.cmdOptions());
		}

		return ret;
	}

	private static void applySyncDelay(List<String> ret, MongoCmdOptions cmdOptions) {
		int syncDelay = cmdOptions.syncDelay();
		if (!cmdOptions.useDefaultSyncDelay()) {
			ret.add("--syncdelay=" + syncDelay);
		}
	}

	private static void applyTextSearch(List<String> ret, MongoCmdOptions cmdOptions) {
		if (cmdOptions.enableTextSearch()) {
			ret.add("--setParameter");
			ret.add("textSearchEnabled=true");
		}
	}

	public static List<String> enhanceCommandLinePlattformSpecific(Distribution distribution, List<String> commands) {
		if (NUMA.isNUMA(new SupportConfig(Command.MongoD), distribution.platform())) {
			switch (distribution.platform()) {
			case Linux:
				List<String> ret = new ArrayList<>();
				ret.add("numactl");
				ret.add("--interleave=all");
				ret.addAll(commands);
				return ret;
			default:
				logger.warn("NUMA Plattform detected, but not supported.");
			}
		}
		return commands;
	}

}

package su.rumishistem.rumi_backup_system.Server;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

public class Config {
	public static class SQL {
		public static String Host = "";
		public static int Port = 0;
		public static String DB = "";
		public static String User = "";
		public static String Password = "";
	}

	public static class DIR {
		public static String Temp = "";
		public static String Backup = "";
	}

	public static class Syslog {
		public static String Host = "";
	}

	public static void load() throws IOException {
		HashMap<String, Object> config = new HashMap<>();

		Path path = Path.of("./Config.ini");
		BufferedReader br = Files.newBufferedReader(path);

		try {
			String field = null;
			String line;
			while ((line = br.readLine()) != null) {
				if (line.equals("")) continue;

				if (line.startsWith("[") && line.endsWith("]")) {
					field = line.substring(1, line.length() - 1);
				} else {
					int index = line.indexOf('=');
					String key = field + "." + line.substring(0, index);
					String value = line.substring(index + 1);

					if (value.startsWith("\"") && value.endsWith("\"")) {
						config.put(key, value.substring(1, value.length() - 1));
					} else if (value.equals("true") || value.equals("false")) {
						config.put(key, value.equals("true"));
					} else {
						config.put(key, Integer.parseInt(value));
					}
				}
			}
		} finally {
			br.close();
		}

		SQL.Host = (String)config.get("SQL.HOST");
		SQL.Port = (int)config.get("SQL.PORT");
		SQL.DB = (String)config.get("SQL.DB");
		SQL.User = (String)config.get("SQL.USER");
		SQL.Password = (String)config.get("SQL.PASSWORD");

		DIR.Temp = (String)config.get("DIR.TEMP");
		DIR.Backup = (String)config.get("DIR.BACKUP");

		Syslog.Host = (String)config.get("SYSLOG.HOST");
	}
}

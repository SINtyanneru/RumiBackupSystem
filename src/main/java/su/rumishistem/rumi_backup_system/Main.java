package su.rumishistem.rumi_backup_system;

import java.io.IOException;

import su.rumishistem.rumi_backup_system.Client.Client;
import su.rumishistem.rumi_backup_system.Server.Server;

public class Main {
	public static final int CLIENT_PORT = 56562;
	public static final int NODE_PORT = 56563;

	public static void main(String[] args) throws IOException {
		if (args.length < 1) return;

		if (args[0].equals("server")) {
			//サーバースタート
			Server.main(args);
		} else {
			Client.main(args);
		}
	}
}

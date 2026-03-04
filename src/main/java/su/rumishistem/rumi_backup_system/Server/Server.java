package su.rumishistem.rumi_backup_system.Server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import su.rumishistem.rumi_java_sql.SQL;
import su.rumishistem.rumi_java_sql.SQLC;
import su.rumishistem.rumi_java_sql.SQLValue;

public class Server {
	public static void main(String[] args) {
		System.out.println("RBS Server");

		//設定
		try {
			Config.load();
		} catch (Exception ex) {
			ex.printStackTrace();
			System.err.println("設定ﾌｧｲﾙを読み込めませんでした。");
			System.exit(1);
			return;
		}

		//SQL
		SQL.connect(
			Config.SQL.Host,
			String.valueOf(Config.SQL.Port),
			Config.SQL.DB,
			Config.SQL.User,
			Config.SQL.Password
		);

		SQLMigration.check();

		if (args.length < 2) return;

		try {
			switch (args[1]) {
				case "start": {
					RBSServer.start();
					return;
				}

				case "bucket": {
					if (args.length < 3) throw new IllegalArgumentException("bucketの後にcreate delete listの何れかをつけてください");
					switch (args[2]) {
						case "create": {
							if (args.length < 4)  throw new IllegalArgumentException("ﾊﾞｹｯﾄ名を記述してください");
							int id = ThreadLocalRandom.current().nextInt(1000, 9000);
							String bucket_name = args[3];
							int keep_gen = 10;

							System.out.print("ﾊﾞｹｯﾄ「"+bucket_name+"」を作成しますか？ y/n >");
							if (!y_n_check()) return;

							SQL.new_auto_commit_connection().update_execute("INSERT INTO `BUCKET` (`ID`, `NAME`, `KEEP_GEN`) VALUES (?, ?, ?);", new Object[]{
								id, bucket_name, keep_gen
							});

							System.out.println("OK");
							return;
						}

						case "delete": {
							if (args.length < 4)  throw new IllegalArgumentException("ﾊﾞｹｯﾄ名を記述してください");
							String bucket_name = args[3];

							System.out.print("ﾊﾞｹｯﾄ「"+bucket_name+"」を削除しますか？ y/n >");
							if (!y_n_check()) return;

							SQL.new_auto_commit_connection().update_execute("DELETE FROM `BUCKET` WHERE `NAME` = ? LIMIT 1;", new Object[]{
								bucket_name
							});

							System.out.println("OK");
							return;
						}

						case "list": {
							Map<String, SQLValue>[] list = SQL.new_auto_commit_connection().select_execute("""
								SELECT
									b.*,
									(SELECT
										COUNT(*)
									FROM
										`BACKUP`
									WHERE
										`BUCKET` = b.ID
									) AS `GEN_COUNT`,
									(SELECT
										`CREATE_AT`
									FROM
										`BACKUP`
									WHERE
										`BUCKET` = b.ID
									ORDER BY
										`CREATE_AT` ASC
									LIMIT 1
									) AS `OLD`,
									(SELECT
										`CREATE_AT`
									FROM
										`BACKUP`
									WHERE
										`BUCKET` = b.ID
									ORDER BY
										`CREATE_AT` DESC
									LIMIT 1
									) AS `LAST`
								FROM
									`BUCKET` AS b;
							""", new Object[0]);

							for (Map<String, SQLValue> row:list) {
								String id = row.get("ID").as_string();
								String name = row.get("NAME").as_string();
								int keep_gen = row.get("KEEP_GEN").as_int();
								long saved_gen = row.get("GEN_COUNT").as_long();
								LocalDateTime last_saved = ((Timestamp)row.get("LAST").as_object()).toLocalDateTime();
								LocalDateTime old_saved = ((Timestamp)row.get("OLD").as_object()).toLocalDateTime();

								System.out.println("┌[" + name + "](" + id + ")");
								System.out.println("├保持数: " + keep_gen + "\\" + saved_gen);
								System.out.println("├最新: " + last_saved.format(DateTimeFormatter.ISO_DATE_TIME));
								System.out.println("└最古: " + old_saved.format(DateTimeFormatter.ISO_DATE_TIME));
							}
							return;
						}

						default:
							throw new IllegalArgumentException("createか、deleteか、listのみ使用可能です。");
					}
				}

				default:
					throw new IllegalArgumentException("そのようなコマンドはありません。");
			}
		} catch (IOException ex) {
			ex.printStackTrace();
		} catch (SQLException ex) {
			ex.printStackTrace();
		}
	}

	public static String to_hex(byte[] binary) {
		StringBuilder sb = new StringBuilder(binary.length * 2);
		for (byte b : binary) {
			sb.append(String.format("%02X ", b));
		}
		return sb.toString();
	}

	private static boolean y_n_check() throws IOException{
		BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
		String line = br.readLine();
		if (line == null) return false;
		if (line.equalsIgnoreCase("y")) return true;
		return false;
	}
}

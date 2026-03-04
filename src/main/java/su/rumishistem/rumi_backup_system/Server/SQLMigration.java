package su.rumishistem.rumi_backup_system.Server;

import java.sql.SQLException;

import su.rumishistem.rumi_java_sql.SQL;
import su.rumishistem.rumi_java_sql.SQLC;

public class SQLMigration {
	private static final String[][] script_list = new String[][]{
		//初回状態
		new String[]{
			"""
			CREATE TABLE `BUCKET` (
				`ID` BIGINT PRIMARY KEY,
				`NAME` VARCHAR(256) NOT NULL,
				`KEEP_GEN` INT(255) NOT NULL
			) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
			""",
			"""
			CREATE TABLE `BACKUP` (
				`ID` BIGINT PRIMARY KEY,
				`BUCKET` BIGINT NOT NULL,
				`CREATE_AT` DATETIME NOT NULL,
				`SIZE` BIGINT NOT NULL,
				`MIMETYPE` VARCHAR(256) NOT NULL,
				CONSTRAINT fk_backup_bucket
					FOREIGN KEY (`BUCKET`)
					REFERENCES BUCKET(`ID`)
					ON DELETE CASCADE
					ON UPDATE CASCADE
			) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
			"""
		}
	};
	public static final int LATEST_SQL_VERSION = script_list.length;

	public static void check() {
		try {
			SQLC sql = SQL.new_connection();

			int sql_version;

			//テーブルが初回状態か
			if (sql.select_execute("SHOW TABLES;", new Object[0]).length == 0) {
				System.out.println("SQLが初回状態です、METAﾃｰﾌﾞﾙを作成します...");
				sql.update_execute("""
					CREATE TABLE IF NOT EXISTS `META` (
						`ID` VARCHAR(256) NOT NULL,
						`VALUE` TEXT NOT NULL,
						PRIMARY KEY (`ID`)
					) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
				""", new Object[0]);

				sql.update_execute("INSERT INTO `META` (`ID`, `VALUE`) VALUES ('VERSION', '0');", new Object[0]);
				sql.commit();

				sql_version = 0;
			} else {
				String v = sql.select_execute("SELECT `VALUE` FROM `META` WHERE `ID` = 'VERSION';", new Object[0])[0].get("VALUE").as_string();
				sql_version = Integer.parseInt(v);
			}
			sql.close();

			System.out.println("SQLﾊﾞｰｼﾞｮﾝ: " + sql_version);
			if (SQLMigration.LATEST_SQL_VERSION > sql_version) {
				System.out.println(sql_version + "から" + SQLMigration.LATEST_SQL_VERSION + "までﾏｲｸﾞﾚｰｼｮﾝを実行します。");
				SQLMigration.migration(sql_version);
			}
		} catch (SQLException ex) {
			ex.printStackTrace();
			System.err.println("SQLﾁｪｯｸに失敗しました。");
			System.exit(1);
			return;
		}
	}

	public static void migration(int now_version) {
		SQLC sql;
		try {
			sql = SQL.new_connection();
		} catch (SQLException ex) {
			ex.printStackTrace();
			System.err.println("SQLへの接続ができません。");
			System.exit(1);
			return;
		}

		try {
			//ﾏｲｸﾞﾚｰｼｮﾝ
			for (int v = now_version; v < script_list.length; v++) {
				System.out.println("[ MIG ] SQLﾊﾞｰｼﾞｮﾝ" + v + "のﾏｲｸﾞﾚｰｼｮﾝ中...");
				for (int i = 0; i < script_list[v].length; i++) {
					String script = script_list[v][i];
					sql.update_execute(script, new Object[0]);
					System.out.println("[ MIG ] SQL EXEC >" + script);
				}
			}

			//SQLバージョンを上げる
			sql.update_execute("UPDATE `META` SET `VALUE` = ? WHERE `ID` = 'VERSION'; ", new Object[]{LATEST_SQL_VERSION});

			sql.commit();
			sql.close();

			System.out.println("ﾏｲｸﾞﾚｰｼｮﾝ完了。");
		} catch (SQLException ex) {
			ex.printStackTrace();
			System.err.println("SQLﾏｲｸﾞﾚｰｼｮﾝ失敗！");
			System.exit(1);
		}
	}
}

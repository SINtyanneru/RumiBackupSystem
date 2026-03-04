package su.rumishistem.rumi_backup_system.Server;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.Map;
import su.rumishistem.rumi_backup_system.Main;
import su.rumishistem.rumi_backup_system.Server.Type.ClientType;
import su.rumishistem.rumi_backup_system.Tool.Binary;
import su.rumishistem.rumi_java_sql.*;

public class RBSServer {
	private static final byte[] WELCOME_MESSAGE = new byte[]{0x25, 'R', 'B', 'S', 0x00, 0x00, 0x00, 0x00, 0x03, '1', '.', '0'};

	public static void start() {
		try {
			ServerSocket tcp = new ServerSocket(Main.CLIENT_PORT);
			System.out.println("ｸﾗｲｱﾝﾄｻｰﾊﾞｰ起動");

			//ｼｬｯﾄﾀﾞｳﾝ時
			Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						tcp.close();
					} catch (IOException ex) {
						ex.printStackTrace();
					}
				}
			}));

			while (true) {
				final Socket socket = tcp.accept();
				new Thread(new Runnable() {
					@Override
					public void run() {
						try {
							InputStream in = socket.getInputStream();
							BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
							ClientType type = null;

							out.write(WELCOME_MESSAGE);
							out.flush();

							ByteArrayOutputStream baos;
							byte[] buffer = new byte[4096];
							int rl;
							while ((rl = in.read(buffer)) != -1) {
								baos = new ByteArrayOutputStream();
								baos.write(buffer, 0, rl);
								byte[] data = baos.toByteArray();
								baos.close();

								//ハンドシェイク
								if (type == null) {
									if (data.length == 2) {
										if (data[0] == 0x01) {
											switch (data[1]) {
												case 0x01:
													type = ClientType.Client;
													out.write(new byte[]{0x20});
													out.flush();
													continue;
												case 0x02:
													type = ClientType.Node;
													out.write(new byte[]{0x20});
													out.flush();
													continue;
											}
										}
									}

									socket.close();
									return;
								}

								ByteArrayInputStream bais = new ByteArrayInputStream(data);

								//バックアップ命令
								if (bais.readNBytes(1)[0] == 0x02) {
									//0x00を飛ばす
									bais.skipNBytes(1);

									long bucket_id;
									String bucket_name;
									int keep_gen;

									long backup_id = System.currentTimeMillis();
									String mimetype;
									long file_size;

									//バケット名
									byte[] bucket_length_binary = bais.readNBytes(4);
									int bucket_length = Binary.binary_to_int(bucket_length_binary);
									bucket_name = new String(bais.readNBytes(bucket_length), StandardCharsets.UTF_8);

									//Mimetype
									byte[] mimetype_length_binary = bais.readNBytes(4);
									int mimetype_length = Binary.binary_to_int(mimetype_length_binary);
									mimetype = new String(bais.readNBytes(mimetype_length), StandardCharsets.UTF_8);

									//ファイルサイズ
									file_size = Binary.binary_to_long(bais.readNBytes(8));

									//バケット名からバケットIDを取得
									Map<String, SQLValue>[] select_bucket = SQL.new_auto_commit_connection().select_execute("SELECT `ID`, `KEEP_GEN` FROM `BUCKET` WHERE `NAME` = ?;", new Object[]{
										bucket_name
									});
									if (select_bucket.length == 0) {
										out.write(new byte[]{0x40});
										out.flush();
										bais.close();
										socket.close();
										return;
									} else {
										bucket_id = select_bucket[0].get("ID").as_long();
										keep_gen = select_bucket[0].get("KEEP_GEN").as_int();
									}

									//受信準備
									out.write(new byte[]{0x10});
									out.flush();
									bais.close();

									//チェックサム
									MessageDigest md5 = MessageDigest.getInstance("MD5");
									//一時ファイル
									File tmp_file = new File(Config.DIR.Temp + backup_id);
									FileOutputStream fos = new FileOutputStream(tmp_file);

									//データ受信
									byte[] receive_buffer = new byte[8192];
									long received_length = 0;
									int receive_rl;
									while ((receive_rl = in.read(receive_buffer)) != -1) {
										md5.update(receive_buffer, 0, receive_rl);
										fos.write(receive_buffer, 0, receive_rl);

										received_length += receive_rl;
										if (file_size == received_length) break;
									}
									fos.close();

									//チェックサム受信待機
									out.write(new byte[]{0x10});
									out.flush();
									byte[] client_checksum = in.readNBytes(16);
									byte[] server_checksum = md5.digest();

									//チェックサムチェック
									for (int i = 16; i < client_checksum.length; i++) {
										if (client_checksum[i] != server_checksum[i]) {
											out.write(new byte[]{0x40});
											out.flush();
											tmp_file.delete();
											return;
										}
									}

									try {
										SQL.new_auto_commit_connection().update_execute("INSERT INTO `BACKUP` (`ID`, `BUCKET`, `CREATE_AT`, `SIZE`, `MIMETYPE`) VALUES (?, ?, NOW(), ?, ?)", new Object[]{
											backup_id, bucket_id, file_size, mimetype
										});
									} catch (SQLException ex) {
										ex.printStackTrace();

										tmp_file.delete();
										out.write(new byte[]{0x50});
										out.flush();
										socket.close();
										return;
									}

									Files.move(tmp_file.toPath(), Path.of(Config.DIR.Backup + backup_id));

									//世代管理
									Map<String, SQLValue>[] old_backup = SQL.new_auto_commit_connection().select_execute("""
										SELECT
											`ID`,
											`CREATE_AT`
										FROM (
											SELECT *,
												ROW_NUMBER() OVER (ORDER BY CREATE_AT DESC) AS rn,
												COUNT(*) OVER () AS total
											FROM BACKUP
										) t
										WHERE
											rn > ?
										AND
											`BUCKET` = ?;
									""", new Object[]{
										keep_gen,
										bucket_id
									});
									if (old_backup.length != 0) {
										SQLC sql = SQL.new_connection();

										//古いものを削除
										for (Map<String, SQLValue> row:old_backup) {
											String id = row.get("ID").as_string();
											File f = new File(Config.DIR.Backup + id);
											if (f.exists()) f.delete();

											sql.update_execute("DELETE FROM `BACKUP` WHERE `ID` = ? LIMIT 1;", new Object[]{id});
										}

										sql.commit();
									}

									//成功
									out.write(new byte[]{0x20});
									out.flush();
								}
							}
						} catch (Exception ex) {
							ex.printStackTrace();
						}
					}
				}).run();
			}
		} catch (IOException ex) {
			ex.printStackTrace();
		}
	}
}

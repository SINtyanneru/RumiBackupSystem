package su.rumishistem.rumi_backup_system.Server;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.zip.DeflaterOutputStream;

import org.checkerframework.checker.units.qual.min;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

import com.github.luben.zstd.ZstdOutputStream;
import su.rumishistem.rumi_backup_system.Main;
import su.rumishistem.rumi_backup_system.Server.Type.BackupQueue;
import su.rumishistem.rumi_backup_system.Server.Type.ClientType;
import su.rumishistem.rumi_backup_system.Tool.Binary;
import su.rumishistem.rumi_java_logger.RumiJavaLogger;
import su.rumishistem.rumi_java_logger.SeverityLevel;
import su.rumishistem.rumi_java_sql.*;

public class RBSServer {
	private static final byte[] WELCOME_MESSAGE = new byte[]{0x25, 'R', 'B', 'S', 0x00, 0x00, 0x00, 0x00, 0x03, '1', '.', '0'};
	private static BlockingQueue<BackupQueue> queue_list = new LinkedBlockingQueue<>();

	public static void start() {
		try {
			RumiJavaLogger logger = new RumiJavaLogger();
			logger.hijack_std(SeverityLevel.Debug);
			logger.set_syslog_server(Config.Syslog.Host);

			new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						while (true) {
							BackupQueue queue = queue_list.take();
							long bucket_id = queue.bucket_id;
							long backup_id = queue.backup_id;
							String mimetype = queue.mimetype;
							File tmp_file = new File(Config.DIR.Temp + backup_id);
							FileInputStream fis;

							byte[] buffer = new byte[8192];
							int rl;
							long raw_size = tmp_file.length();
							long deflate_size = 0;
							long zstd_size = 0;
							long xz_size = 0;

							//Deflate
							File deflate_file = new File(Config.DIR.Temp + backup_id + ".deflate");
							try {
								FileOutputStream deflate_fos = new FileOutputStream(deflate_file);
								DeflaterOutputStream deflate = new DeflaterOutputStream(deflate_fos);
								fis = new FileInputStream(tmp_file);
								while ((rl = fis.read(buffer)) != -1) {
									deflate.write(buffer, 0, rl);
								}
								deflate.close();
								fis.close();
								deflate_size = deflate_file.length();
							} catch (IOException ex) {
								ex.printStackTrace();
								//後始末
								deflate_file.delete();
								tmp_file.delete();
								return;
							}

							//Zstd生成
							File zstd_file = new File(Config.DIR.Temp + backup_id + ".zstd");
							try {
								FileOutputStream zstd_fos = new FileOutputStream(zstd_file);
								ZstdOutputStream zstd = new ZstdOutputStream(zstd_fos);
								zstd.setLevel(14);
								fis = new FileInputStream(tmp_file);
								while ((rl = fis.read(buffer)) != -1) {
									zstd.write(buffer, 0, rl);
								}
								zstd.close();
								fis.close();
								zstd_size = zstd_file.length();
							} catch (IOException ex) {
								ex.printStackTrace();
								//後始末
								deflate_file.delete();
								zstd_file.delete();
								tmp_file.delete();
								return;
							}

							//XZ
							File xz_file = new File(Config.DIR.Temp + backup_id + ".xz");
							try {
								FileOutputStream xz_fos = new FileOutputStream(xz_file);
								XZOutputStream xz = new XZOutputStream(xz_fos, new LZMA2Options(6));
								fis = new FileInputStream(tmp_file);
								while ((rl = fis.read(buffer)) != -1) {
									xz.write(buffer, 0, rl);
								}
								xz.close();
								fis.close();
								xz_size = xz_file.length();
							} catch (IOException ex) {
								ex.printStackTrace();
								//後始末
								deflate_file.delete();
								zstd_file.delete();
								xz_file.delete();
								tmp_file.delete();
								return;
							}

							//一番小さいものを探す
							long min_size = raw_size;
							File min_compress_file = tmp_file;
							String compress_type = "RAW";
							if (deflate_size < min_size) {
								min_size = deflate_size;
								min_compress_file = deflate_file;
								compress_type = "DEFLATE";
							}
							if (zstd_size < min_size) {
								min_size = zstd_size;
								min_compress_file = zstd_file;
								compress_type = "ZSTD";
							}
							if (xz_size < min_size) {
								min_size = xz_size;
								min_compress_file = xz_file;
								compress_type = "XZ";
							}

							SQLC sql = SQL.new_connection();
							try {
								sql.update_execute("INSERT INTO `BACKUP` (`ID`, `BUCKET`, `CREATE_AT`, `COMPRESS_SIZE`, `ORIGINAL_SIZE`, `MIMETYPE`, `COMPRESS_TYPE`) VALUES (?, ?, NOW(), ?, ?, ?, ?)", new Object[]{
									backup_id, bucket_id, tmp_file.length(), min_size, mimetype, compress_type
								});
							} catch (SQLException ex) {
								ex.printStackTrace();
								//後始末
								deflate_file.delete();
								zstd_file.delete();
								xz_file.delete();
								tmp_file.delete();
								return;
							}

							//バックアップ保存
							try {
								Files.move(min_compress_file.toPath(), Path.of(Config.DIR.Backup + backup_id));
							} catch (IOException ex) {
								ex.printStackTrace();
								//後始末
								sql.rollback();
								deflate_file.delete();
								zstd_file.delete();
								xz_file.delete();
								tmp_file.delete();
								return;
							}

							//世代ローテート
							int keep_gen = sql.select_execute("SELECT `ID`, `KEEP_GEN` FROM `BUCKET` WHERE `ID` = ?;", new Object[]{bucket_id})[0].get("KEEP_GEN").as_int();
							Map<String, SQLValue>[] old_backup = sql.select_execute("""
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
								//古いものを削除
								for (Map<String, SQLValue> row:old_backup) {
									String id = row.get("ID").as_string();
									LocalDateTime date = ((Timestamp)row.get("CREATE_AT").as_object()).toLocalDateTime();
									File f = new File(Config.DIR.Backup + id);
									if (f.exists()) f.delete();

									sql.update_execute("DELETE FROM `BACKUP` WHERE `ID` = ? LIMIT 1;", new Object[]{id});
									logger.print(SeverityLevel.Debug, "ﾊﾞｯｸｱｯﾌﾟﾛｰﾃｰｼｮﾝ: " + bucket_id + "から" + id + "(" + date.format(DateTimeFormatter.ISO_DATE_TIME) + ")" + "を削除");
								}
							}

							//OK
							sql.commit();
							sql.close();

							logger.print(SeverityLevel.Informational, "ﾊﾞｯｸｱｯﾌﾟを処理しました: " + backup_id + " ﾊﾞｹｯﾄ:" + bucket_id);

							//後始末
							if (tmp_file.exists()) tmp_file.delete();
							if (deflate_file.exists()) deflate_file.delete();
							if (zstd_file.exists()) zstd_file.delete();
							if (xz_file.exists()) xz_file.delete();
						}
					} catch (InterruptedException ex) {
						ex.printStackTrace();
					} catch (SQLException ex) {
						ex.printStackTrace();
					}
				}
			}).start();

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
													logger.print(SeverityLevel.Debug, "ｸﾗｲｱﾝﾄ接続: " + socket.getInetAddress().toString());
													continue;
												case 0x02:
													type = ClientType.Node;
													out.write(new byte[]{0x20});
													out.flush();
													logger.print(SeverityLevel.Debug, "ﾉｰﾄﾞ接続: " + socket.getInetAddress().toString());
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
									}

									//受信準備
									out.write(new byte[]{0x10});
									out.flush();
									bais.close();

									logger.print(SeverityLevel.Debug, "ﾊﾞｯｸｱｯﾌﾟ受信中: " + socket.getInetAddress().toString() + "から" + bucket_name + "(" + bucket_id + ")へ");

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

									queue_list.put(new BackupQueue(backup_id, bucket_id, mimetype));

									//成功
									out.write(new byte[]{0x20});
									out.flush();

									logger.print(SeverityLevel.Debug, "ﾊﾞｯｸｱｯﾌﾟ受信完了: " + socket.getInetAddress().toString() + "から" + bucket_name + "(" + bucket_id + ")へ" + backup_id + "として" + file_size + "ﾊﾞｲﾄ");
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

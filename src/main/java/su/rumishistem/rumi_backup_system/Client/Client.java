package su.rumishistem.rumi_backup_system.Client;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import org.apache.commons.compress.archivers.tar.*;
import org.apache.commons.io.IOUtils;
import su.rumishistem.rumi_backup_system.Tool.Binary;

public class Client {
	public static void main(String[] args) {
		if (args.length < 2) return;

		if (args[0].equals("backup")) {
			if (args.length < 4) return;

			String bucket = args[2];
			File data = null;
			String mimetype = null;
			long size = 0;

			switch (args[3]) {
				case "mysql": {
					if (args.length < 7) throw new IllegalArgumentException("MySQLのユーザー名、パスワード、一時ファイルの書き込み場所が必要です");
					String mysql_user = args[4];
					String mysql_password = args[5];

					data = new File(args[6] + UUID.randomUUID().toString());
					mimetype = "text/plain";

					ProcessBuilder pb = new ProcessBuilder("/usr/bin/mysqldump", "-u", mysql_user, "--all-databases", "--routines", "--events", "--triggers", "--single-transaction", "--flush-privileges");
					pb.environment().put("MYSQL_PWD", mysql_password);

					try {
						Process p = pb.start();
						InputStream in = p.getInputStream();
						FileOutputStream fos = new FileOutputStream(data);

						byte[] buffer = new byte[8192];
						int rl;
						while ((rl = in.read(buffer)) != -1) {
							fos.write(buffer, 0, rl);
						}

						fos.close();

						int exit = p.waitFor();
						if (exit != 0) {
							throw new IOException("ステータスコード: " + exit);
						}

						size = data.length();
						break;
					} catch (IOException ex) {
						if (data.exists()) data.delete();
						ex.printStackTrace();
						System.exit(1);
						return;
					} catch (InterruptedException ex) {
						if (data.exists()) data.delete();
						ex.printStackTrace();
						System.exit(1);
						return;
					}
				}

				case "psql": {
					if (args.length < 5) throw new IllegalArgumentException("一時ファイルの書き込み場所が必要です");

					data = new File(args[4] + UUID.randomUUID().toString());
					mimetype = "text/plain";

					ProcessBuilder pb = new ProcessBuilder("/usr/bin/pg_dumpall");

					try {
						Process p = pb.start();
						InputStream in = p.getInputStream();
						FileOutputStream fos = new FileOutputStream(data);

						byte[] buffer = new byte[8192];
						int rl;
						while ((rl = in.read(buffer)) != -1) {
							fos.write(buffer, 0, rl);
						}

						fos.close();

						int exit = p.waitFor();
						if (exit != 0) {
							throw new IOException("ステータスコード: " + exit);
						}

						size = data.length();
						break;
					} catch (IOException ex) {
						if (data.exists()) data.delete();
						ex.printStackTrace();
						System.exit(1);
						return;
					} catch (InterruptedException ex) {
						if (data.exists()) data.delete();
						ex.printStackTrace();
						System.exit(1);
						return;
					}
				}

				//ファイル
				default: {
					File backup_target = new File(args[3]);

					//バックアップする対象は存在するか
					if (!backup_target.exists()) {
						System.err.println("ﾊﾞｯｸｱｯﾌﾟ対象「"+backup_target.toString()+"」は存在しません。");
						System.exit(1);
						return;
					}

					if (backup_target.isDirectory()) {
						System.out.println("ﾃﾞｨﾚｸﾄﾘをﾊﾞｯｸｱｯﾌﾟします");
						data = new File("./" + UUID.randomUUID().toString());

						//TAR化
						try {
							OutputStream fout = Files.newOutputStream(data.toPath());
							BufferedOutputStream bos = new BufferedOutputStream(fout);
							TarArchiveOutputStream tar = new TarArchiveOutputStream(bos);

							//POSIX
							tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);

							Files.walk(backup_target.toPath()).forEach((path)->{
								try {
									String entry_name = backup_target.toPath().relativize(path).toString();
									if (entry_name.isEmpty()) return;

									TarArchiveEntry entry = new TarArchiveEntry(entry_name);
									entry.setSize(path.toFile().length());
									tar.putArchiveEntry(entry);

									if (Files.isRegularFile(path)) {
										InputStream is = Files.newInputStream(path);
										try {
											IOUtils.copy(is, tar);
										} finally {
											is.close();
										}
									}

									tar.closeArchiveEntry();
								} catch (IOException ex) {
									ex.printStackTrace();
									System.err.println("TARの作成に失敗しました、ﾌｧｲﾙ: " + path);
									System.exit(1);
									return;
								}
							});

							tar.finish();
							tar.close();

							mimetype = "application/x-tar";
						} catch (IOException ex) {
							ex.printStackTrace();
							System.exit(1);
						}
					} else {
						System.out.println("ﾌｧｲﾙをﾊﾞｯｸｱｯﾌﾟします");

						try {
							mimetype = Files.probeContentType(backup_target.toPath());
							System.out.println("ﾌｧｲﾙのmimeﾀｲﾌﾟ: " + mimetype);

							data = new File("./" + UUID.randomUUID().toString());
							Files.copy(backup_target.toPath(), data.toPath());
						} catch (IOException ex) {
							ex.printStackTrace();
							System.exit(1);
						}
					}
				}
			}

			//ファイルサイズ
			size = data.length();

			try {
				RBSSocket rbs = new RBSSocket(args[1]);

				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				baos.write((byte)0x02);
				baos.write((byte)0x00);

				byte[] bucket_binary = bucket.getBytes(StandardCharsets.UTF_8);
				baos.write(Binary.int_to_binary(bucket_binary.length));
				baos.write(bucket_binary);

				byte[] mimetype_binary = mimetype.getBytes(StandardCharsets.UTF_8);
				baos.write(Binary.int_to_binary(mimetype_binary.length));
				baos.write(mimetype_binary);

				baos.write(Binary.long_to_binary(size));

				byte[] message = baos.toByteArray();
				baos.close();
				if (rbs.send_message(message)[0] != 0x10) {
					System.err.println("ﾊﾞｯｸｱｯﾌﾟの送信許可が出ませんでした。");
					System.exit(1);
					rbs.close();
					return;
				}
				System.out.println("ﾊﾞｯｸｱｯﾌﾟ送信のﾊﾝﾄﾞｼｪｲｸ完了、送信します");

				BufferedInputStream fis = new BufferedInputStream(new FileInputStream(data));
				MessageDigest md5 = MessageDigest.getInstance("MD5");
				try {
					//データ送信
					byte[] buffer = new byte[8192];
					int rl;
					while ((rl = fis.read(buffer)) != -1) {
						byte[] read_data = Arrays.copyOf(buffer, rl);
						rbs.send_data(read_data);
						md5.update(read_data);
					}

					if (rbs.wait_return()[0] != 0x10) {
						System.err.println("ﾊﾞｯｸｱｯﾌﾟの送信に失敗しました、ﾁｪｯｸｻﾑ送信失敗");
						System.exit(1);
						return;
					}
					System.out.println("ﾊﾞｯｸｱｯﾌﾟ送信を送信しました、ﾁｪｯｸｻﾑを送信します: " + Base64.getEncoder().encodeToString(md5.digest()));

					//チェックサム送信
					byte[] checksum = md5.digest();
					if (rbs.send_message(checksum)[0] != 0x20) {
						System.err.println("ﾁｪｯｸｻﾑ送信でエラーが発生しました、ｻｰﾊﾞｰと一致しません。");
						System.exit(1);
						return;
					}

					System.out.println("全て正常に成功しました。");
				} finally {
					//後処理
					fis.close();
					data.delete();
					rbs.close();
				}
			} catch (Exception ex) {
				if (data.exists()) data.delete();
				ex.printStackTrace();
				System.exit(1);
			}
		}
	}
}

package su.rumishistem.rumi_backup_system.Client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Consumer;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.io.IOUtils;

import su.rumishistem.rumi_backup_system.Tool.Binary;

public class Client {
	public static void main(String[] args) {
		if (args.length < 2) return;

		if (args[0].equals("backup")) {
			if (args.length < 4) return;

			String bucket = args[2];
			File backup_target = new File(args[3]);

			//バックアップする対象は存在するか
			if (!backup_target.exists()) {
				System.err.println("ﾊﾞｯｸｱｯﾌﾟ対象「"+backup_target.toString()+"」は存在しません。");
				System.exit(1);
				return;
			}

			File data = null;
			String mimetype = null;
			long size = 0;

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
				ex.printStackTrace();
				System.exit(1);
			}
		}
	}
}

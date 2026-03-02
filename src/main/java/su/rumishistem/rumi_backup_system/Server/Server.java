package su.rumishistem.rumi_backup_system.Server;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import su.rumishistem.rumi_backup_system.Main;
import su.rumishistem.rumi_backup_system.Server.Type.ClientType;
import su.rumishistem.rumi_backup_system.Tool.Binary;

public class Server {
	private static final byte[] WELCOME_MESSAGE = new byte[]{0x25, 'R', 'B', 'S', 0x00, 0x00, 0x00, 0x00, 0x03, '1', '.', '0'};

	public static void main(String[] args) {
		System.out.println("RBS Server");

		try {
			ServerSocket tcp = new ServerSocket(Main.CLIENT_PORT);
			System.out.println("ｸﾗｲｱﾝﾄｻｰﾊﾞｰ起動");

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

									//バケット名
									byte[] bucket_length_binary = bais.readNBytes(4);
									int bucket_length = Binary.binary_to_int(bucket_length_binary);
									String bucket_name = new String(bais.readNBytes(bucket_length), StandardCharsets.UTF_8);

									//Mimetype
									byte[] mimetype_length_binary = bais.readNBytes(4);
									int mimetype_length = Binary.binary_to_int(mimetype_length_binary);
									String mimetype = new String(bais.readNBytes(mimetype_length), StandardCharsets.UTF_8);

									//ファイルサイズ
									long file_size = Binary.binary_to_long(bais.readNBytes(8));

									//受信準備
									out.write(new byte[]{0x10});
									out.flush();
									bais.close();

									//チェックサム
									MessageDigest md5 = MessageDigest.getInstance("MD5");
									//一時ファイル
									File tmp_file = new File("./"+UUID.randomUUID().toString());
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

	public static String to_hex(byte[] binary) {
		StringBuilder sb = new StringBuilder(binary.length * 2);
		for (byte b : binary) {
			sb.append(String.format("%02X ", b));
		}
		return sb.toString();
	}
}

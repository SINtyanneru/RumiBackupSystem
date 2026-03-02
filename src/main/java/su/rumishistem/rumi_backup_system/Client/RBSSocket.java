package su.rumishistem.rumi_backup_system.Client;

import java.io.*;
import java.net.Socket;
import su.rumishistem.rumi_backup_system.Main;

/**
 * RBSサーバーに接続し、通信を行えます。
 */
public class RBSSocket implements Closeable{
	private Socket socket;
	private InputStream in;
	private BufferedOutputStream out;

	/**
	 * RBSサーバーに接続します。
	 * 
	 * @param host サーバーのホスト
	 * @throws IOException 接続失敗
	 */
	public RBSSocket(String host) throws IOException{
		socket = new Socket(host, Main.CLIENT_PORT);
		in = socket.getInputStream();
		out = new BufferedOutputStream(socket.getOutputStream());

		//ハンドシェイク
		byte[] data = read();
		if (data.length == 12 && (data[0] == 0x25 && data[1] == 'R' && data[2] == 'B' && data[3] == 'S' && data[4] == 0x00)) {
			System.out.println("接続しました。");
			System.out.println("ｸﾗｲｱﾝﾄ宣言中...");

			out.write(new byte[]{0x01, 0x01});
			out.flush();

			byte code = (byte)(in.read() & 0xFF);
			if (code == 0x20) {
				System.out.println("ハンドシェイクが完了しました！");
				return;
			}
		}

		throw new RuntimeException("接続先サーバーはRBSではありません。");
	}

	/**
	 * サーバーへメッセージを送信し、応答を待機します。
	 * 内部的にはsend_data()をした後にwait_return()をしています。
	 * 
	 * @param message メッセージ
	 */
	public byte[] send_message(byte[] message) throws IOException {
		send_data(message);
		return wait_return();
	}

	/**
	 * サーバーからの応答を待機します。
	 * 待機前に、flushを実行しています。
	 */
	public byte[] wait_return() throws IOException{
		out.flush();
		return read();
	}

	/**
	 * データをサーバーへ送信します。
	 * 
	 * @param data データ
	 */
	public void send_data(byte[] data) throws IOException{
		out.write(data);
	}

	@Override
	public void close() throws IOException {
		in.close();
		out.close();
		socket.close();
	}

	private byte[] read() throws IOException{
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		byte[] buffer = new byte[4096];
		int rl = in.read(buffer);
		if (rl == -1) return null;

		baos.write(buffer, 0, rl);

		return baos.toByteArray();
	}
}

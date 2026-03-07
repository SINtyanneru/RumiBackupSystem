package su.rumishistem.rumi_backup_system.Server.Type;

public class BackupQueue {
	public final long backup_id;
	public final long bucket_id;
	public final String mimetype;

	public BackupQueue(long backup_id, long bucket_id, String mimetype) {
		this.backup_id = backup_id;
		this.bucket_id = bucket_id;
		this.mimetype = mimetype;
	}
}

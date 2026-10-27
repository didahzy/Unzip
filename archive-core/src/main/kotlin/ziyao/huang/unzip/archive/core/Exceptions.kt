package ziyao.huang.unzip.archive.core

/** 畸形/损坏压缩包 */
class ArchiveFormatException(message: String) : Exception(message)

/** 加密文件头场景用户取消密码 */
class PasswordCancelledException(message: String) : Exception(message)

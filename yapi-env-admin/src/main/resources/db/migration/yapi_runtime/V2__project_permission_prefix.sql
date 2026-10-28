-- Projenin yetki öneki (merkezdeki project.permission_prefix): broker reddedilen isteklerde kullanıcının projeye ait
-- bir yetkisi olup olmadığını bununla yazar. Merkez her proje ayarında gönderir; gelmeden önce boş kalır.
ALTER TABLE runtime_project ADD (permission_prefix VARCHAR2(50 CHAR));

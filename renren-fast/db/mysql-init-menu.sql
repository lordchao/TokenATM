TRUNCATE sys_menu ;
INSERT INTO token_atm.sys_menu (parent_id,name,url,perms,`type`,icon,order_num) VALUES
                                                                                    (0,'sys management',NULL,NULL,0,'system',0),
                                                                                    (1,'sys role','sys/user',NULL,1,'admin',1),
                                                                                    (1,'role managment','sys/role',NULL,1,'role',2),
                                                                                    (1,'menu managment','sys/menu',NULL,1,'menu',3),
                                                                                    (6,'check',NULL,'sys:schedule:list,sys:schedule:info',2,NULL,0),
                                                                                    (6,'add new',NULL,'sys:schedule:save',2,NULL,0),
                                                                                    (6,'update',NULL,'sys:schedule:update',2,NULL,0),
                                                                                    (6,'delete',NULL,'sys:schedule:delete',2,NULL,0),
                                                                                    (6,'pause',NULL,'sys:schedule:pause',2,NULL,0),
                                                                                    (6,'recover',NULL,'sys:schedule:resume',2,NULL,0);
INSERT INTO token_atm.sys_menu (parent_id,name,url,perms,`type`,icon,order_num) VALUES
                                                                                    (6,'do',NULL,'sys:schedule:run',2,NULL,0),
                                                                                    (6,'sys log',NULL,'sys:schedule:log',2,NULL,0),
                                                                                    (2,'check now',NULL,'sys:user:list,sys:user:info',2,NULL,0),
                                                                                    (2,'add',NULL,'sys:user:save,sys:role:select',2,NULL,0),
                                                                                    (2,'change',NULL,'sys:user:update,sys:role:select',2,NULL,0),
                                                                                    (2,'delete',NULL,'sys:user:delete',2,NULL,0),
                                                                                    (3,'chek',NULL,'sys:role:list,sys:role:info',2,NULL,0),
                                                                                    (3,'add',NULL,'sys:role:save,sys:menu:list',2,NULL,0),
                                                                                    (3,'update',NULL,'sys:role:update,sys:menu:list',2,NULL,0),
                                                                                    (3,'delete',NULL,'sys:role:delete',2,NULL,0);
INSERT INTO token_atm.sys_menu (parent_id,name,url,perms,`type`,icon,order_num) VALUES
                                                                                    (4,'check',NULL,'sys:menu:list,sys:menu:info',2,NULL,0),
                                                                                    (4,'add',NULL,'sys:menu:save,sys:menu:select',2,NULL,0),
                                                                                    (4,'update',NULL,'sys:menu:update,sys:menu:select',2,NULL,0),
                                                                                    (4,'delete',NULL,'sys:menu:delete',2,NULL,0),
                                                                                    (0,'Token Management','','',0,'',0),
                                                                                    (25,'All Students','token/students','',1,'',0),
                                                                                    (0,'Student Token','','',0,'',0),
                                                                                    (27,'Past Resubmission','token/pastResubmission','',1,'',0),
                                                                                    (27,'Available Resubmission','token/student','',1,'',0),
                                                                                    (25,'Token Logs','token/logs','',1,'',0);
INSERT INTO token_atm.sys_menu (parent_id,name,url,perms,`type`,icon,order_num) VALUES
                                                                                    (25,'Student Logs','token/student-log','',1,'',0),
                                                                                    (27,'Token History','token/oneStudentLog','',1,'',0);




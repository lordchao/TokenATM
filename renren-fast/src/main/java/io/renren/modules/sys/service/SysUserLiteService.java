package io.renren.modules.sys.service;

import com.baomidou.mybatisplus.extension.service.IService;
import io.renren.modules.sys.entity.SysUserEntity;
import io.renren.modules.sys.entity.SysUserLiteEntity;

public interface SysUserLiteService extends IService<SysUserLiteEntity> {
    SysUserLiteEntity queryByUserName(String username);
}

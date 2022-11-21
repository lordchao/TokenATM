package io.renren.modules.sys.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.renren.modules.sys.dao.SysUserLiteDao;
import io.renren.modules.sys.entity.SysUserLiteEntity;
import io.renren.modules.sys.service.SysUserLiteService;
import org.springframework.stereotype.Service;



@Service("sysUserLiteService")
public class SysUserLiteServiceImpl extends ServiceImpl<SysUserLiteDao, SysUserLiteEntity> implements SysUserLiteService {

    @Override
    public SysUserLiteEntity queryByUserName(String username) {
        return null;
    }

}

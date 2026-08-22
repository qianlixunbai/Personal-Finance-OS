package com.financeos.module.category.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financeos.module.category.entity.Category;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CategoryMapper extends BaseMapper<Category> {
    @Select("""
            <script>
            SELECT * FROM categories WHERE id IN
            <foreach collection="categoryIds" item="categoryId" open="(" separator="," close=")">#{categoryId}</foreach>
            </script>
            """)
    List<Category> selectByIds(@Param("categoryIds") List<Long> categoryIds);

    @Select("""
            <script>
            SELECT * FROM categories WHERE id IN
            <foreach collection="categoryIds" item="categoryId" open="(" separator="," close=")">#{categoryId}</foreach>
            AND (user_id = #{userId} OR (user_id IS NULL AND is_system = true))
            </script>
            """)
    List<Category> selectVisibleByIds(@Param("userId") Long userId, @Param("categoryIds") List<Long> categoryIds);
}

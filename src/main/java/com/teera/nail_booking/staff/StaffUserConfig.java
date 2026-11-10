package com.teera.nail_booking.staff;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/** บัญชีล็อกอินของช่าง: ผู้ใช้ staff / รหัสผ่านตั้งที่ staff.password (ค่าเริ่มต้นใช้ทดลองเท่านั้น) */
@Configuration
public class StaffUserConfig {

    @Bean
    public InMemoryUserDetailsManager staffUsers(@Value("${staff.password:perfect1234}") String password) {
        UserDetails staff = User.withUsername("staff")
                .password("{noop}" + password)
                .roles("STAFF")
                .build();
        return new InMemoryUserDetailsManager(staff);
    }
}

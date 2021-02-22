package de.metas.ui.web.login.json;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Value;

/*
 * #%L
 * metasfresh-webui-api
 * %%
 * Copyright (C) 2016 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

@Value
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE, isGetterVisibility = JsonAutoDetect.Visibility.NONE, setterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonDeserialize(builder = JSONLoginRole.JSONLoginRoleBuilder.class)
public class JSONLoginRole
{
	String caption;
	int roleId;
	int tenantId;
	int orgId;

	String key;

	@Builder
	private JSONLoginRole(
			final String caption,
			final int roleId,
			final int tenantId,
			final int orgId,
			final String key)
	{
		this.caption = caption;
		this.roleId = roleId;
		this.tenantId = tenantId;
		this.orgId = orgId;
		this.key = key != null ? key : roleId + "_" + tenantId + "_" + orgId;
	}

	@JsonPOJOBuilder(withPrefix = "")
	public static class JSONLoginRoleBuilder {}

}

package com.dianping.cat.report.page.heartbeat;

import org.unidal.web.mvc.ActionContext;
import org.unidal.web.mvc.payload.annotation.FieldMeta;

import com.dianping.cat.report.ReportPage;
import com.dianping.cat.report.page.AbstractReportPayload;

public class Payload extends AbstractReportPayload<Action> {
	@FieldMeta("op")
	private Action m_action;

	@FieldMeta("type")
	private String m_type;

	private String m_realIp;

	public Payload() {
		super(ReportPage.HEARTBEAT);
	}

	@Override
	public Action getAction() {
		return m_action;
	}

	public String getRealIp() {
		return m_realIp;
	}

	@Override
   public String getReportType() {
	   return "day";
   }

	public String getType() {
		return m_type;
	}

	public void setAction(String action) {
		m_action = Action.getByName(action, Action.VIEW);
	}

	public void setRealIp(String realIp) {
		m_realIp = realIp;
	}

	public void setType(String type) {
		m_type = type;
	}

	@Override
	public void validate(ActionContext<?> ctx) {
		if (m_action == null) {
			m_action = Action.VIEW;
		}
	}
}
